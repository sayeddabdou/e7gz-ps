package ps.e7gz.operations.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.EmergencyScope;
import ps.e7gz.common.codes.EmergencyStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.operations.api.DeclaredEmergencyIntervals;
import ps.e7gz.operations.api.OperationalOverlaps;

/**
 * {@code operations.emergency}, {@code operations.maintenance_window} and {@code operations.venue_closure} on the
 * command's transaction ({@link CommandContext#jdbc()}). All three periods are canonical {@code [)} ranges, so
 * {@code &&} gives half-open overlap. The E7-33 interval read shares check 5's Emergency selection.
 */
@Component
class JdbcOperationalOverlaps implements OperationalOverlaps, DeclaredEmergencyIntervals {

    /** The Emergency states that are not resolved yet; an ENDED or CONVERTED Emergency no longer blocks. */
    static final List<String> UNRESOLVED =
            List.of(EmergencyStatus.ACTIVE.name(), EmergencyStatus.AWAITING_CONFIRMATION.name());

    /**
     * The Emergencies check 5 counts: unresolved, scoped on the Venue or on the Resource, declared interval overlapping
     * {@code [start, end)}. {@code ck_emergency_period_canonical}: {@code declared_period = [declared_at,
     * expected_end_at)}. Shared by {@link #EMERGENCY} and the E7-33 {@link #EMERGENCY_INTERVALS}, so the two always
     * select the same rows.
     */
    static final String COUNTED_EMERGENCIES = """
                  FROM operations.emergency e
                 WHERE e.status IN (:unresolved)
                   AND ((e.scope = :venueScope AND e.venue_id = :venueId)
                        OR (e.scope = :resourceScope AND e.resource_id = :resourceId))
                   AND e.declared_period && tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)')
            """;

    static final String EMERGENCY = """
            SELECT EXISTS (
                SELECT 1
            """ + COUNTED_EMERGENCIES + ")";

    /** Their declared intervals, merged by {@code range_agg} and returned in order (arch 05 section 5.2). */
    static final String EMERGENCY_INTERVALS = """
            SELECT lower(m.declared) AS start_at, upper(m.declared) AS end_at
              FROM unnest((SELECT range_agg(e.declared_period)
            """ + COUNTED_EMERGENCIES + """
                   )) AS m(declared)
             ORDER BY lower(m.declared)
            """;

    static final String MAINTENANCE = """
            SELECT EXISTS (
                SELECT 1
                  FROM operations.maintenance_window m
                 WHERE m.resource_id = :resourceId
                   AND m.period && tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)'))
            """;

    static final String CLOSURE = """
            SELECT EXISTS (
                SELECT 1
                  FROM operations.venue_closure c
                 WHERE c.venue_id = :venueId
                   AND c.period && tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)'))
            """;

    @Override
    public boolean declaredEmergencyOverlaps(CommandContext command, UUID venueId, UUID resourceId, Instant start,
                                             Instant end) {
        return countedEmergencies(command, EMERGENCY, venueId, resourceId, start, end)
                .query(Boolean.class).single();
    }

    @Override
    public List<Interval> blocking(CommandContext command, UUID venueId, UUID resourceId, Instant start, Instant end) {
        return countedEmergencies(command, EMERGENCY_INTERVALS, venueId, resourceId, start, end)
                .query((rs, n) -> new Interval(rs.getObject("start_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("end_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private static JdbcClient.StatementSpec countedEmergencies(CommandContext command, String sql, UUID venueId,
                                                               UUID resourceId, Instant start, Instant end) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(resourceId, "resourceId");
        requirePeriod(start, end);
        return command.jdbc().sql(sql)
                .param("unresolved", UNRESOLVED)
                .param("venueScope", EmergencyScope.VENUE.name())
                .param("venueId", venueId)
                .param("resourceScope", EmergencyScope.RESOURCE.name())
                .param("resourceId", resourceId)
                .param("start", utc(start))
                .param("end", utc(end));
    }

    @Override
    public boolean maintenanceOverlaps(CommandContext command, UUID resourceId, Instant start, Instant end) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(resourceId, "resourceId");
        requirePeriod(start, end);
        return command.jdbc().sql(MAINTENANCE)
                .param("resourceId", resourceId)
                .param("start", utc(start))
                .param("end", utc(end))
                .query(Boolean.class).single();
    }

    @Override
    public boolean closureOverlaps(CommandContext command, UUID venueId, Instant start, Instant end) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(venueId, "venueId");
        requirePeriod(start, end);
        return command.jdbc().sql(CLOSURE)
                .param("venueId", venueId)
                .param("start", utc(start))
                .param("end", utc(end))
                .query(Boolean.class).single();
    }

    private static void requirePeriod(Instant start, Instant end) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start: [" + start + ", " + end + ")");
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
