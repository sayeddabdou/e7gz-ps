package ps.e7gz.operations.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.EmergencyScope;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.operations.api.OperationalStateAt;

/**
 * {@code operations.owner_review} and {@code operations.emergency} as of an instant, on the command's transaction. The
 * intervals are those of {@code tournament.highest_current_cancellation_reason}: {@code entered_at} /
 * {@code declared_at <= at} and {@code resolved_at} / {@code ended_at} null or after {@code at} (an ENDED or CONVERTED
 * Emergency always has its {@code ended_at}, {@code ck_emergency_terminal_shape}).
 */
@Component
class JdbcOperationalStateAt implements OperationalStateAt {

    static final String OWNER_REVIEW_AT = """
            SELECT EXISTS (
                SELECT 1 FROM operations.owner_review o
                 WHERE o.resource_id = :resourceId
                   AND o.entered_at <= :at
                   AND (o.resolved_at IS NULL OR o.resolved_at > :at))
            """;

    static final String RESOURCE_EMERGENCY_AT = """
            SELECT EXISTS (
                SELECT 1 FROM operations.emergency e
                 WHERE e.scope = :scope AND e.resource_id = :resourceId
                   AND e.declared_at <= :at
                   AND (e.ended_at IS NULL OR e.ended_at > :at))
            """;

    static final String VENUE_EMERGENCY_AT = """
            SELECT EXISTS (
                SELECT 1 FROM operations.emergency e
                 WHERE e.scope = :scope AND e.venue_id = :venueId
                   AND e.declared_at <= :at
                   AND (e.ended_at IS NULL OR e.ended_at > :at))
            """;

    static final String CLOSURE_PREVENTS_SERVICE = """
            SELECT EXISTS (
                SELECT 1 FROM operations.venue_closure c
                 WHERE c.venue_id = :venueId
                   AND c.period && tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)'))
            """;

    @Override
    public boolean closurePreventsService(CommandContext command, UUID venueId, Instant start, Instant end) {
        return command.jdbc().sql(CLOSURE_PREVENTS_SERVICE)
                .param("venueId", Objects.requireNonNull(venueId, "venueId"))
                .param("start", utc(start))
                .param("end", utc(end))
                .query(Boolean.class).single();
    }

    @Override
    public boolean ownerReviewAt(CommandContext command, UUID resourceId, Instant at) {
        return command.jdbc().sql(OWNER_REVIEW_AT)
                .param("resourceId", Objects.requireNonNull(resourceId, "resourceId"))
                .param("at", utc(at))
                .query(Boolean.class).single();
    }

    @Override
    public boolean resourceEmergencyAt(CommandContext command, UUID resourceId, Instant at) {
        return command.jdbc().sql(RESOURCE_EMERGENCY_AT)
                .param("scope", EmergencyScope.RESOURCE.name())
                .param("resourceId", Objects.requireNonNull(resourceId, "resourceId"))
                .param("at", utc(at))
                .query(Boolean.class).single();
    }

    @Override
    public boolean venueEmergencyAt(CommandContext command, UUID venueId, Instant at) {
        return command.jdbc().sql(VENUE_EMERGENCY_AT)
                .param("scope", EmergencyScope.VENUE.name())
                .param("venueId", Objects.requireNonNull(venueId, "venueId"))
                .param("at", utc(at))
                .query(Boolean.class).single();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "at"), ZoneOffset.UTC);
    }
}
