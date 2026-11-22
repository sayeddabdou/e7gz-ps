package ps.e7gz.trust.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.TrustEventType;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.trust.api.BookingOutcomes;
import ps.e7gz.trust.api.TrustReconciler;

/**
 * {@code trust.trust_event} and {@code trust.trust_projection} on the command's transaction. The database assigns the
 * event sequence and checks the subject, the effective time and the shared command instant
 * ({@code trust.prepare_trust_event_insert}); the {@link TrustReconciler} then recomputes the projection by full replay
 * and settles every newly derived consequence (E7-70, arch 07 section 4).
 * Every booking outcome is written by the one statement below (E7-59 no-show, E7-61 completion, E7-69 interruption).
 */
@Component
class JdbcBookingOutcomes implements BookingOutcomes {

    static final String INSERT_OUTCOME = """
            INSERT INTO trust.trust_event (id, event_type, subject_user_id, occurred_at, effective_at, booking_id)
            VALUES (:id, :type, :userId, :occurredAt, :effectiveAt, :bookingId)
            """;

    @Override
    public void recordNoShow(CommandContext command, UUID customerId, UUID bookingId, Instant startAt) {
        record(command, TrustEventType.BOOKING_NO_SHOW, customerId, bookingId, startAt);
    }

    private final TrustReconciler reconciler;

    JdbcBookingOutcomes(TrustReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Override
    public void recordCompletion(CommandContext command, UUID customerId, UUID bookingId, Instant startAt,
                                 AttendanceResult result) {
        TrustEventType type = switch (Objects.requireNonNull(result, "result")) {
            case ATTENDED -> TrustEventType.BOOKING_ATTENDED;
            case ATTENDANCE_UNCONFIRMED -> TrustEventType.BOOKING_ATTENDANCE_UNCONFIRMED;
            case ATTENDED_INTERRUPTED, ABSENT_NO_SHOW, NOT_APPLICABLE ->
                    throw new IllegalArgumentException("A completion never resolves to " + result);
        };
        record(command, type, customerId, bookingId, startAt);
    }

    @Override
    public void recordInterruption(CommandContext command, UUID customerId, UUID bookingId, Instant startAt) {
        record(command, TrustEventType.BOOKING_ATTENDED_INTERRUPTED, customerId, bookingId, startAt);
    }

    @Override
    public void recordAttendanceProtection(CommandContext command, UUID customerId, UUID bookingId,
                                           Instant startAt) {
        record(command, TrustEventType.BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION, customerId, bookingId, startAt);
    }

    private void record(CommandContext command, TrustEventType type, UUID customerId, UUID bookingId,
                               Instant startAt) {
        Objects.requireNonNull(customerId, "customerId");
        command.jdbc().sql(INSERT_OUTCOME)
                .param("id", UUID.randomUUID())
                .param("type", type.name())
                .param("userId", customerId)
                .param("occurredAt", utc(command.commandNow()))
                .param("effectiveAt", utc(startAt))
                .param("bookingId", Objects.requireNonNull(bookingId, "bookingId"))
                .update();
        reconciler.reconcile(command, customerId);                                       // arch 07 section 4
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "instant"), ZoneOffset.UTC);
    }
}
