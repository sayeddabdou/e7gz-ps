package ps.e7gz.trust.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.TrustEventType;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.trust.api.NoShowOutcomes;

/**
 * {@code trust.trust_event} and {@code trust.trust_projection} on the command's transaction. The database assigns the
 * event sequence and checks the subject, the effective time and the shared command instant
 * ({@code trust.prepare_trust_event_insert}); {@code trust.refresh_projection} recomputes the projection by full replay.
 */
@Component
class JdbcNoShowOutcomes implements NoShowOutcomes {

    static final String INSERT_NO_SHOW = """
            INSERT INTO trust.trust_event (id, event_type, subject_user_id, occurred_at, effective_at, booking_id)
            VALUES (:id, :type, :userId, :occurredAt, :effectiveAt, :bookingId)
            """;

    static final String REFRESH_PROJECTION = "SELECT trust.refresh_projection(:userId)";

    @Override
    public void recordNoShow(CommandContext command, UUID customerId, UUID bookingId, Instant startAt) {
        Objects.requireNonNull(customerId, "customerId");
        command.jdbc().sql(INSERT_NO_SHOW)
                .param("id", UUID.randomUUID())
                .param("type", TrustEventType.BOOKING_NO_SHOW.name())
                .param("userId", customerId)
                .param("occurredAt", utc(command.commandNow()))
                .param("effectiveAt", utc(startAt))
                .param("bookingId", Objects.requireNonNull(bookingId, "bookingId"))
                .update();
        command.jdbc().sql(REFRESH_PROJECTION).param("userId", customerId).query().singleRow();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "instant"), ZoneOffset.UTC);
    }
}
