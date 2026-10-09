package ps.e7gz.venue.internal;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.geo.Coordinates;
import ps.e7gz.common.validation.TextFields;
import ps.e7gz.common.validation.VenuePhones;
import ps.e7gz.geography.api.AreaLookup;
import ps.e7gz.messaging.api.Outbox;

/** Venues for the Platform Admin: onboarding, venue information and marketplace status (FR-ADM-02, FR-VEN-07). */
@Service
public class Venues {

    public static final int DEFAULT_PAGE_SIZE = 20;

    private final CommandRunner runner;
    private final AuditWriter audit;
    private final Outbox outbox;
    private final AreaLookup areas;
    private final JdbcClient jdbc;

    Venues(CommandRunner runner, AuditWriter audit, Outbox outbox, AreaLookup areas, DataSource appDataSource) {
        this.runner = runner;
        this.audit = audit;
        this.outbox = outbox;
        this.areas = areas;
        this.jdbc = JdbcClient.create(appDataSource);
    }

    /** The venue's shared account, if created (E7-24 creates it). */
    public record VenueAccountSummary(UUID id, UUID venueId, String loginIdentifier, VenueAccountStatus status) {
    }

    public record Page(List<Venue> items, String nextCursor) {
    }

    public record VenueDetail(Venue venue, VenueAccountSummary account) {
    }

    /** New venue information as the admin entered it. */
    public record NewVenue(UUID areaId, String name, String address, Coordinates location, String phone,
                           List<Activity> activities) {
    }

    /** Changed venue information; null means unchanged. */
    public record VenueChanges(UUID areaId, String name, String address, Coordinates location, String phone,
                               List<Activity> activities) {
    }

    /**
     * All venues in any marketplace status, newest first (then by id). The cursor is the opaque id of the last venue of
     * the previous page (arch 09 section 1.2); creation time never changes, so editing a venue cannot move it between
     * pages (PO decision 2 Oct 2026).
     */
    /** One activity of the closed list and how many venues offer it. */
    public record ActivityCount(Activity activity, int venueCount) {
    }

    /**
     * FR-ADM-01 Activities, view only (PO decision 2 Oct 2026, E7-22): all three activities in their declared order,
     * each with the number of venues of any marketplace status that offer it ({@code venue.venue_activity}).
     */
    public List<ActivityCount> activities() {
        Map<String, Integer> counts = new HashMap<>();
        jdbc.sql("SELECT activity, count(*)::integer AS venues FROM venue.venue_activity GROUP BY activity")
                .query(rs -> {
                    counts.put(rs.getString("activity"), rs.getInt("venues"));
                });
        return EnumSet.allOf(Activity.class).stream()
                .map(activity -> new ActivityCount(activity, counts.getOrDefault(activity.name(), 0))).toList();
    }

    public Page list(String cursor, Integer limit) {
        int size = limit == null ? DEFAULT_PAGE_SIZE : limit;
        UUID after = cursor == null || cursor.isBlank() ? null : decodeCursor(cursor);
        List<Venue> rows = jdbc.sql(Venue.SELECT + "\n" + """
                         WHERE CAST(:after AS uuid) IS NULL
                            OR (v.created_at, v.id) < (SELECT c.created_at, c.id FROM venue.venue c WHERE c.id = :after)
                         ORDER BY v.created_at DESC, v.id DESC
                         LIMIT :limit""")
                .param("after", after).param("limit", size + 1).query(Venue::map).list();
        boolean more = rows.size() > size;
        List<Venue> page = more ? rows.subList(0, size) : rows;
        return new Page(page, more ? encodeCursor(page.getLast().id()) : null);
    }

    public VenueDetail get(UUID venueId) {
        Venue venue = jdbc.sql(Venue.SELECT + " WHERE v.id = :id").param("id", venueId).query(Venue::map).optional()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        Optional<VenueAccountSummary> account = jdbc.sql("""
                        SELECT id, venue_id, login_identifier, account_status FROM iam.venue_account
                         WHERE venue_id = :venue""")
                .param("venue", venueId)
                .query((rs, n) -> new VenueAccountSummary(rs.getObject("id", UUID.class),
                        rs.getObject("venue_id", UUID.class), rs.getString("login_identifier"),
                        VenueAccountStatus.valueOf(rs.getString("account_status"))))
                .optional();
        return new VenueDetail(venue, account.orElse(null));
    }

    public Venue create(UUID adminId, NewVenue venue) {
        requireArea(venue.areaId());
        return runner.run(new VenueCommands.Create(adminId, CorrelationId.require(), venue.areaId(),
                venue.name().strip(), venue.address().strip(), venue.location(), phone(venue.phone()),
                activities(venue.activities())));
    }

    public Venue update(UUID adminId, UUID venueId, VenueChanges changes) {
        requireVenue(venueId);
        if (changes.areaId() != null) {
            requireArea(changes.areaId());
        }
        return runner.run(new VenueCommands.Update(adminId, CorrelationId.require(), venueId, changes.areaId(),
                TextFields.optional(changes.name(), "name"), TextFields.optional(changes.address(), "address"),
                changes.location(), changes.phone() == null ? null : phone(changes.phone()),
                changes.activities() == null ? null : activities(changes.activities())));
    }

    public Venue changeStatus(UUID adminId, UUID venueId, MarketplaceAction action, String reason) {
        requireVenue(venueId);
        return runner.run(new VenueCommands.ChangeStatus(adminId, CorrelationId.require(), venueId, action,
                reason.strip(), audit, outbox));
    }

    /** The venue phone in E.164 form; anything else is VALIDATION_FAILED on {@code phone}. */
    private static String phone(String raw) {
        return VenuePhones.toE164(raw).orElseThrow(() -> new ApiException(ApiErrorCode.VALIDATION_FAILED, "phone"));
    }

    /** A venue offers at least one activity (FR-ADM-02 onboarding covers activities). */
    private static Set<Activity> activities(List<Activity> requested) {
        if (requested.isEmpty()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "activities");
        }
        return EnumSet.copyOf(requested);
    }

    /** {@code admin.begin_command} locks the venue row, so an unknown id is answered before the command opens. */
    private void requireVenue(UUID venueId) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM venue.venue WHERE id = :id)").param("id", venueId)
                .query(Boolean.class).single();
        if (!exists) {
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        }
    }

    /** An unknown area is a bad reference in the request; an inactive one is the database's AREA_INACTIVE. */
    private void requireArea(UUID areaId) {
        if (!areas.exists(areaId)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "areaId");
        }
    }

    static String encodeCursor(UUID id) {
        ByteBuffer bytes = ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
    }

    static UUID decodeCursor(String cursor) {
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
