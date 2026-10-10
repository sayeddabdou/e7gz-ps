package ps.e7gz.notification.internal;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.notification.api.VenueBrowserPush;
import ps.e7gz.notification.internal.webpush.SubscriptionKeys;
import ps.e7gz.notification.internal.webpush.WebPushProperties;

/**
 * The Venue Dashboard's Web Push subscriptions (ADR-014, arch 01 section 14 A6; routes: PO decision 5 Oct 2026, E7-39).
 * One {@code notification.venue_push_subscription} row per browser; revoked, never deleted (arch 08 section 4).
 */
@Component
public class VenuePushSubscriptions implements VenueBrowserPush {

    public record Subscription(UUID id, Instant createdAt) {
    }

    private final CommandRunner runner;
    private final JdbcClient jdbc;
    private final WebPushProperties properties;

    VenuePushSubscriptions(CommandRunner runner, DataSource appDataSource, WebPushProperties properties) {
        this.runner = runner;
        this.jdbc = JdbcClient.create(appDataSource);
        this.properties = properties;
    }

    /** The VAPID public key the browser subscribes with ({@code applicationServerKey}). */
    public String vapidPublicKey() {
        return properties.vapid().publicKey().strip();
    }

    /**
     * Registers this browser. The endpoint must be https on a known push service and the keys real Web Push keys
     * ({@code VALIDATION_FAILED} with the field otherwise). An endpoint already active for this account with the same
     * keys is seen again ({@code last_seen_at} moves), not duplicated. An endpoint active for another venue account (a
     * shared computer) or with other keys moves here: that row is revoked, never deleted, and a new one is created
     * (endpoint and keys are immutable, {@code guard_venue_push_subscription}; one active row per endpoint,
     * {@code ux_venue_push_active}).
     */
    public Subscription subscribe(UUID venueAccountId, String endpoint, String p256dh, String auth,
                                  String userAgent) {
        String target = endpoint.strip();
        if (!allowedEndpoint(target)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "endpoint");
        }
        if (!SubscriptionKeys.isP256dh(p256dh)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "keys.p256dh");
        }
        if (!SubscriptionKeys.isAuth(auth)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "keys.auth");
        }
        String agent = userAgent == null || userAgent.isBlank() ? null : userAgent.strip();
        return runner.run(new Subscribe(venueAccountId, venueOf(venueAccountId), target, p256dh.strip(),
                auth.strip(), agent));
    }

    /** Stops push to one of the account's browsers (e.g. on logout); an already-revoked one changes nothing. */
    public void revoke(UUID venueAccountId, UUID subscriptionId) {
        runner.run(new Revoke(venueAccountId, venueOf(venueAccountId), subscriptionId));
    }

    /** Every active browser of the account, on the disable command's transaction (revoked, never deleted). */
    @Override
    public int revokeAll(CommandContext context, UUID venueAccountId) {
        return context.jdbc().sql("""
                        UPDATE notification.venue_push_subscription
                           SET revoked_at = greatest(:now, last_seen_at)
                         WHERE venue_account_id = :account AND revoked_at IS NULL""")
                .param("now", utc(context.commandNow())).param("account", venueAccountId).update();
    }

    /**
     * The account's venue, the command's lock. Read here, not through venue.api: the venue module already depends on
     * notifications, so the reverse would be a module cycle.
     */
    private UUID venueOf(UUID venueAccountId) {
        return jdbc.sql("SELECT venue_id FROM iam.venue_account WHERE id = :id").param("id", venueAccountId)
                .query(UUID.class).optional().orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }

    private boolean allowedEndpoint(String endpoint) {
        try {
            URI uri = new URI(endpoint);
            return "https".equals(uri.getScheme()) && uri.getRawUserInfo() == null
                    && properties.allowsEndpointHost(uri.getHost());
        } catch (Exception malformed) {
            return false;
        }
    }

    private record Subscribe(UUID venueAccountId, UUID venueId, String endpoint, String p256dh, String auth,
                             String userAgent) implements Command<Subscription> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().venues(venueId).build());
        }

        @Override
        public Subscription execute(CommandContext context) {
            // One active row per endpoint (ux_venue_push_active): concurrent registrations of it queue here.
            context.jdbc().sql("SELECT pg_advisory_xact_lock(hashtextextended('venue_push:' || :endpoint, 0))")
                    .param("endpoint", endpoint).query().singleRow();
            OffsetDateTime now = utc(context.commandNow());
            Optional<Subscription> mine = context.jdbc().sql("""
                            UPDATE notification.venue_push_subscription
                               SET last_seen_at = greatest(:now, last_seen_at)
                             WHERE endpoint = :endpoint AND revoked_at IS NULL AND venue_account_id = :account
                               AND p256dh_key = :p256dh AND auth_key = :auth
                            RETURNING id, created_at""")
                    .param("now", now).param("endpoint", endpoint).param("account", venueAccountId)
                    .param("p256dh", p256dh).param("auth", auth)
                    .query((rs, row) -> new Subscription(rs.getObject("id", UUID.class),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                    .optional();
            if (mine.isPresent()) {
                return mine.get();
            }
            context.jdbc().sql("""
                            UPDATE notification.venue_push_subscription SET revoked_at = greatest(:now, last_seen_at)
                             WHERE endpoint = :endpoint AND revoked_at IS NULL""")
                    .param("now", now).param("endpoint", endpoint).update();
            UUID id = UUID.randomUUID();
            context.jdbc().sql("""
                            INSERT INTO notification.venue_push_subscription
                                   (id, venue_account_id, endpoint, p256dh_key, auth_key, user_agent, last_seen_at,
                                    created_at)
                            VALUES (:id, :account, :endpoint, :p256dh, :auth, :agent, :now, :now)""")
                    .param("id", id).param("account", venueAccountId).param("endpoint", endpoint)
                    .param("p256dh", p256dh).param("auth", auth).param("agent", userAgent).param("now", now)
                    .update();
            return new Subscription(id, context.commandNow());
        }

        @Override
        public String toString() {
            return "Subscribe[venueAccountId=" + venueAccountId + ", <endpoint and keys redacted>]";
        }
    }

    private record Revoke(UUID venueAccountId, UUID venueId, UUID subscriptionId) implements Command<Void> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().venues(venueId).build());
        }

        @Override
        public Void execute(CommandContext context) {
            Optional<Boolean> active = context.jdbc().sql("""
                            SELECT revoked_at IS NULL FROM notification.venue_push_subscription
                             WHERE id = :id AND venue_account_id = :account FOR UPDATE""")
                    .param("id", subscriptionId).param("account", venueAccountId).query(Boolean.class).optional();
            if (active.isEmpty()) {
                throw new ApiException(ApiErrorCode.NOT_FOUND);
            }
            if (active.get()) {
                context.jdbc().sql("""
                                UPDATE notification.venue_push_subscription
                                   SET revoked_at = greatest(:now, last_seen_at)
                                 WHERE id = :id""")
                        .param("now", utc(context.commandNow())).param("id", subscriptionId).update();
            }
            return null;
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
