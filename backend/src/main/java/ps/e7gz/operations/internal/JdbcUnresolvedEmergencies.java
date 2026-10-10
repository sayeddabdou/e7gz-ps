package ps.e7gz.operations.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.EmergencyScope;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.operations.api.UnresolvedEmergencies;

/**
 * {@code operations.emergency} on the command's transaction. Unresolved means what check 5 means
 * ({@link JdbcOperationalOverlaps#UNRESOLVED}); unlike check 5 it does not look at the declared interval, because an
 * unresolved Emergency keeps its cause in force past it (its selling block is a state, FR-AVL-07).
 */
@Component
class JdbcUnresolvedEmergencies implements UnresolvedEmergencies {

    static final String VENUE_EMERGENCY = """
            SELECT EXISTS (
                SELECT 1
                  FROM operations.emergency e
                 WHERE e.scope = :scope
                   AND e.venue_id = :venueId
                   AND e.status IN (:unresolved)
                   AND e.declared_at <= :at)
            """;

    static final String RESOURCE_EMERGENCY = """
            SELECT EXISTS (
                SELECT 1
                  FROM operations.emergency e
                 WHERE e.scope = :scope
                   AND e.resource_id = :resourceId
                   AND e.status IN (:unresolved)
                   AND e.declared_at <= :at)
            """;

    @Override
    public boolean venueEmergency(CommandContext command, UUID venueId, Instant at) {
        Objects.requireNonNull(command, "command");
        return command.jdbc().sql(VENUE_EMERGENCY)
                .param("scope", EmergencyScope.VENUE.name())
                .param("venueId", Objects.requireNonNull(venueId, "venueId"))
                .param("unresolved", JdbcOperationalOverlaps.UNRESOLVED)
                .param("at", utc(at))
                .query(Boolean.class).single();
    }

    @Override
    public boolean resourceEmergency(CommandContext command, UUID resourceId, Instant at) {
        Objects.requireNonNull(command, "command");
        return command.jdbc().sql(RESOURCE_EMERGENCY)
                .param("scope", EmergencyScope.RESOURCE.name())
                .param("resourceId", Objects.requireNonNull(resourceId, "resourceId"))
                .param("unresolved", JdbcOperationalOverlaps.UNRESOLVED)
                .param("at", utc(at))
                .query(Boolean.class).single();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "at"), ZoneOffset.UTC);
    }
}
