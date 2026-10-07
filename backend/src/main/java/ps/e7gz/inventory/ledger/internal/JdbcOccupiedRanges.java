package ps.e7gz.inventory.ledger.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.inventory.ledger.api.OccupiedRanges;

/**
 * {@code range_agg} over the active rows' immutable {@code planned_range}: the database merges overlapping and adjacent
 * ranges into one multirange per Resource, and {@code unnest} returns its ranges in order (arch 05 section 5.2).
 */
@Component
class JdbcOccupiedRanges implements OccupiedRanges {

    static final String OCCUPIED = """
            SELECT m.resource_id, lower(m.occupied) AS start_at, upper(m.occupied) AS end_at
              FROM (SELECT rr.resource_id, unnest(range_agg(rr.planned_range)) AS occupied
                      FROM inventory.resource_reservation rr
                     WHERE rr.resource_id IN (:resourceIds)
                       AND rr.active_flag
                       AND rr.planned_range && tstzrange(CAST(:from AS timestamptz), CAST(:to AS timestamptz), '[)')
                     GROUP BY rr.resource_id) m
             ORDER BY m.resource_id, lower(m.occupied)
            """;

    @Override
    public List<Occupied> occupied(CommandContext command, Collection<UUID> resourceIds, Instant from, Instant to) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(resourceIds, "resourceIds");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("to must be after from: [" + from + ", " + to + ")");
        }
        if (resourceIds.isEmpty()) {
            return List.of();
        }
        return command.jdbc().sql(OCCUPIED)
                .param("resourceIds", List.copyOf(resourceIds))
                .param("from", utc(from))
                .param("to", utc(to))
                .query((rs, n) -> new Occupied(rs.getObject("resource_id", UUID.class),
                        rs.getObject("start_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("end_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
