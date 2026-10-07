package ps.e7gz.operations.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.EmergencyScope;
import ps.e7gz.operations.api.ScheduledOperations;

/**
 * {@code operations.maintenance_window}, {@code operations.venue_closure} and {@code operations.emergency} in one
 * statement on the app connection (E7-112). All three periods are canonical {@code [)} ranges. The Emergency selection
 * is check 5's ({@link JdbcOperationalOverlaps#UNRESOLVED}); the predicate's own reads are not used here.
 */
@Component
class JdbcScheduledOperations implements ScheduledOperations {

    static final String OVERLAPPING = """
            WITH w AS (SELECT tstzrange(CAST(:from AS timestamptz), CAST(:to AS timestamptz), '[)') AS r)
            SELECT 'MAINTENANCE' AS kind, m.resource_id, lower(m.period) AS start_at, upper(m.period) AS end_at
              FROM operations.maintenance_window m, w
             WHERE m.resource_id = ANY(:resourceIds)
               AND m.period && w.r
            UNION ALL
            SELECT 'VENUE_CLOSURE', NULL, lower(c.period), upper(c.period)
              FROM operations.venue_closure c, w
             WHERE c.venue_id = :venueId
               AND c.period && w.r
            UNION ALL
            SELECT CASE WHEN e.scope = :venueScope THEN 'VENUE_EMERGENCY' ELSE 'RESOURCE_EMERGENCY' END,
                   e.resource_id, lower(e.declared_period), upper(e.declared_period)
              FROM operations.emergency e, w
             WHERE e.status IN (:unresolved)
               AND e.venue_id = :venueId
               AND (e.scope = :venueScope OR (e.scope = :resourceScope AND e.resource_id = ANY(:resourceIds)))
               AND e.declared_period && w.r
             ORDER BY start_at, end_at, kind
            """;

    private final JdbcClient jdbc;

    JdbcScheduledOperations(DataSource appDataSource) {
        this.jdbc = JdbcClient.create(appDataSource);
    }

    @Override
    public List<Interval> overlapping(UUID venueId, Collection<UUID> resourceIds, Instant from, Instant to) {
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(resourceIds, "resourceIds");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("to must be after from: [" + from + ", " + to + ")");
        }
        return jdbc.sql(OVERLAPPING)
                .param("venueId", venueId)
                .param("resourceIds", resourceIds.stream().distinct().toArray(UUID[]::new))
                .param("unresolved", JdbcOperationalOverlaps.UNRESOLVED)
                .param("venueScope", EmergencyScope.VENUE.name())
                .param("resourceScope", EmergencyScope.RESOURCE.name())
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .query((rs, n) -> new Interval(Kind.valueOf(rs.getString("kind")),
                        rs.getObject("resource_id", UUID.class),
                        rs.getObject("start_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("end_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}
