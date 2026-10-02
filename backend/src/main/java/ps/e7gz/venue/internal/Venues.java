package ps.e7gz.venue.internal;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
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
import ps.e7gz.geography.api.AreaLookup;

/** Venues for the Platform Admin: onboarding, venue information and marketplace status (FR-ADM-02, FR-VEN-07). */
@Service
public class Venues {

    public static final int DEFAULT_PAGE_SIZE = 20;

    private final CommandRunner runner;
    private final AuditWriter audit;
    private final AreaLookup areas;
    private final JdbcClient jdbc;

    Venues(CommandRunner runner, AuditWriter audit, AreaLookup areas, DataSource appDataSource) {
        this.runner = runner;
        this.audit = audit;
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
     * All venues in any marketplace status, by name. The cursor is the opaque id of the last venue of the previous
     * page (arch 09 section 1.2).
     */
    public Page list(String cursor, Integer limit) {
        int size = limit == null ? DEFAULT_PAGE_SIZE : limit;
        UUID after = cursor == null || cursor.isBlank() ? null : decodeCursor(cursor);
        List<Venue> rows = jdbc.sql(Venue.SELECT + "\n" + """
                         WHERE CAST(:after AS uuid) IS NULL
                            OR (v.name, v.id) > (SELECT c.name, c.id FROM venue.venue c WHERE c.id = :after)
                         ORDER BY v.name, v.id
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
                venue.name().strip(), venue.address().strip(), venue.location(), venue.phone().strip(),
                activities(venue.activities())));
    }

    public Venue update(UUID adminId, UUID venueId, VenueChanges changes) {
        requireVenue(venueId);
        if (changes.areaId() != null) {
            requireArea(changes.areaId());
        }
        return runner.run(new VenueCommands.Update(adminId, CorrelationId.require(), venueId, changes.areaId(),
                blankToNull(changes.name()), blankToNull(changes.address()), changes.location(),
                blankToNull(changes.phone()),
                changes.activities() == null ? null : activities(changes.activities())));
    }

    public Venue changeStatus(UUID adminId, UUID venueId, MarketplaceAction action, String reason) {
        requireVenue(venueId);
        return runner.run(new VenueCommands.ChangeStatus(adminId, CorrelationId.require(), venueId, action,
                reason.strip(), audit));
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
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
