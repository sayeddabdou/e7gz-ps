package ps.e7gz.notification.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.DevicePlatform;
import ps.e7gz.common.codes.NotificationRecipientType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.notification.api.CustomerPushDevices;

/**
 * The customer's notification list and push devices (E7-39 contract, FR-NTF-02; arch 08 section 4). Open to every
 * signed-in customer, phone verified or not (PO decision 10 Oct 2026).
 */
@Component
public class CustomerNotifications implements CustomerPushDevices {

    static final int DEFAULT_PAGE_SIZE = 20;

    private static final TypeReference<Map<String, Object>> PARAMS = new TypeReference<>() {
    };

    public record Item(UUID id, String code, UUID bookingId, UUID tournamentId, Map<String, Object> params,
                       Instant readAt, Instant createdAt) {
    }

    public record Page(List<Item> items, String nextCursor) {
    }

    public record Device(UUID id, DevicePlatform platform, Instant createdAt) {
    }

    private final JdbcClient jdbc;
    private final CommandRunner runner;
    private final ObjectMapper json;

    CustomerNotifications(DataSource appDataSource, CommandRunner runner, ObjectMapper json) {
        this.jdbc = JdbcClient.create(appDataSource);
        this.runner = runner;
        this.json = json;
    }

    /**
     * The customer's in-app notifications, newest first (then by id). The cursor is the opaque id of the last item of
     * the previous page (arch 09 section 1.2); a notification never changes its creation time, so reading one cannot
     * move it between pages. Only notifications with an in-app delivery are listed (an email-only one is not).
     */
    public Page list(UUID userId, String cursor, Integer limit) {
        return list(NotificationRecipientType.CUSTOMER, userId, cursor, limit);
    }

    /** The in-app list of one recipient (a customer, or a venue for its Dashboard - E7-55), as {@link #list}. */
    Page list(NotificationRecipientType recipientType, UUID recipientId, String cursor, Integer limit) {
        int size = limit == null ? DEFAULT_PAGE_SIZE : limit;
        UUID after = decodeCursor(cursor);
        List<Item> rows = jdbc.sql("""
                        SELECT n.id, n.code, n.booking_id, n.tournament_id, n.params::text AS params, n.read_at,
                               n.created_at
                          FROM notification.notification n
                         WHERE n.recipient_type = :type AND n.recipient_id = :user
                           AND EXISTS (SELECT 1 FROM notification.notification_delivery d
                                        WHERE d.notification_id = n.id AND d.channel = 'IN_APP')
                           AND (CAST(:after AS uuid) IS NULL
                                OR (n.created_at, n.id) < (SELECT c.created_at, c.id FROM notification.notification c
                                                            WHERE c.id = :after AND c.recipient_type = :type
                                                              AND c.recipient_id = :user))
                         ORDER BY n.created_at DESC, n.id DESC
                         LIMIT :limit""")
                .param("type", recipientType.name()).param("user", recipientId).param("after", after)
                .param("limit", size + 1)
                .query((rs, row) -> new Item(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getObject("booking_id", UUID.class), rs.getObject("tournament_id", UUID.class),
                        params(rs.getString("params")), instant(rs.getObject("read_at", OffsetDateTime.class)),
                        instant(rs.getObject("created_at", OffsetDateTime.class))))
                .list();
        boolean more = rows.size() > size;
        List<Item> page = more ? rows.subList(0, size) : rows;
        return new Page(page, more ? encodeCursor(page.getLast().id()) : null);
    }

    /** Marks one of the customer's notifications read; an already-read one keeps its first read time. */
    public void markRead(UUID userId, UUID notificationId) {
        markRead(NotificationRecipientType.CUSTOMER, userId, EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE,
                LockSet.builder().users(userId).build(), notificationId);
    }

    /**
     * Marks one of the recipient's notifications read under {@code locks} (E7-55: also a venue's); another recipient's
     * is {@code NOT_FOUND}.
     */
    void markRead(NotificationRecipientType recipientType, UUID recipientId, EligibilityCategory category,
                  LockSet locks, UUID notificationId) {
        runner.run(new MarkRead(recipientType, recipientId, category, locks, notificationId));
    }

    /**
     * Registers this phone's FCM token. A token already active for this customer and platform is seen again (its
     * {@code last_seen_at} moves), not duplicated. A token active for another customer (a shared phone) or for another
     * platform moves here: that row is revoked, never deleted, and a new one is created (PO decision 10 Oct 2026).
     */
    public Device register(UUID userId, String token, DevicePlatform platform) {
        return runner.run(new Register(userId, token, platform));
    }

    /** Revokes one of the customer's devices (e.g. on logout); an already-revoked one changes nothing. */
    public void revoke(UUID userId, UUID deviceId) {
        runner.run(new Revoke(userId, deviceId));
    }

    /** Every active device of the customer, on the caller's command (revoked, never deleted; E7-119). */
    @Override
    public int revokeAll(CommandContext context, UUID customerId) {
        return context.jdbc().sql("""
                        UPDATE notification.device_token SET revoked_at = greatest(:now, last_seen_at)
                         WHERE user_id = :user AND revoked_at IS NULL""")
                .param("now", utc(context.commandNow())).param("user", customerId).update();
    }

    private record MarkRead(NotificationRecipientType recipientType, UUID recipientId, EligibilityCategory category,
                            LockSet locks, UUID notificationId) implements Command<Void> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return category;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(locks);
        }

        @Override
        public Void execute(CommandContext context) {
            Optional<Boolean> unread = context.jdbc().sql("""
                            SELECT read_at IS NULL FROM notification.notification
                             WHERE id = :id AND recipient_type = :type AND recipient_id = :recipient FOR UPDATE""")
                    .param("id", notificationId).param("type", recipientType.name()).param("recipient", recipientId)
                    .query(Boolean.class).optional();
            if (unread.isEmpty()) {
                throw new ApiException(ApiErrorCode.NOT_FOUND);
            }
            if (unread.get()) {
                context.jdbc().sql("""
                                UPDATE notification.notification SET read_at = greatest(:now, created_at)
                                 WHERE id = :id""")
                        .param("now", utc(context.commandNow())).param("id", notificationId).update();
            }
            return null;
        }
    }

    private record Register(UUID userId, String token, DevicePlatform platform) implements Command<Device> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().users(userId).build());
        }

        @Override
        public Device execute(CommandContext context) {
            // One active row per token (ux_device_token_active): concurrent registrations of the token queue here.
            context.jdbc().sql("SELECT pg_advisory_xact_lock(hashtextextended('device_token:' || :token, 0))")
                    .param("token", token).query().singleRow();
            OffsetDateTime now = utc(context.commandNow());
            Optional<Device> mine = context.jdbc().sql("""
                            UPDATE notification.device_token SET last_seen_at = greatest(:now, last_seen_at)
                             WHERE token = :token AND revoked_at IS NULL AND user_id = :user AND platform = :platform
                            RETURNING id, platform, created_at""")
                    .param("now", now).param("token", token).param("user", userId).param("platform", platform.name())
                    .query(CustomerNotifications::device).optional();
            if (mine.isPresent()) {
                return mine.get();
            }
            context.jdbc().sql("""
                            UPDATE notification.device_token SET revoked_at = greatest(:now, last_seen_at)
                             WHERE token = :token AND revoked_at IS NULL""")
                    .param("now", now).param("token", token).update();
            UUID id = UUID.randomUUID();
            context.jdbc().sql("""
                            INSERT INTO notification.device_token (id, user_id, token, platform, last_seen_at, created_at)
                            VALUES (:id, :user, :token, :platform, :now, :now)""")
                    .param("id", id).param("user", userId).param("token", token).param("platform", platform.name())
                    .param("now", now).update();
            return new Device(id, platform, context.commandNow());
        }

        @Override
        public String toString() {
            return "Register[userId=" + userId + ", platform=" + platform + ", <token redacted>]";
        }
    }

    private record Revoke(UUID userId, UUID deviceId) implements Command<Void> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().users(userId).build());
        }

        @Override
        public Void execute(CommandContext context) {
            Optional<Boolean> active = context.jdbc().sql("""
                            SELECT revoked_at IS NULL FROM notification.device_token
                             WHERE id = :id AND user_id = :user FOR UPDATE""")
                    .param("id", deviceId).param("user", userId).query(Boolean.class).optional();
            if (active.isEmpty()) {
                throw new ApiException(ApiErrorCode.NOT_FOUND);
            }
            if (active.get()) {
                context.jdbc().sql("""
                                UPDATE notification.device_token SET revoked_at = greatest(:now, last_seen_at)
                                 WHERE id = :id""")
                        .param("now", utc(context.commandNow())).param("id", deviceId).update();
            }
            return null;
        }
    }

    private static Device device(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Device(rs.getObject("id", UUID.class), DevicePlatform.valueOf(rs.getString("platform")),
                instant(rs.getObject("created_at", OffsetDateTime.class)));
    }

    private Map<String, Object> params(String text) {
        try {
            return text == null ? Map.of() : json.readValue(text, PARAMS);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable notification params", e);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    static String encodeCursor(UUID id) {
        ByteBuffer bytes = ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
    }

    /** Null or blank is the first page; anything else that is not a cursor is VALIDATION_FAILED on {@code cursor}. */
    static UUID decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            if (bytes.length != 16) {
                throw new IllegalArgumentException("cursor length");
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "cursor");
        }
    }
}
