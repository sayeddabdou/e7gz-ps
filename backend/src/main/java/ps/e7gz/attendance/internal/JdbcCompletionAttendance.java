package ps.e7gz.attendance.internal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.AttendanceBookings.Booking;
import ps.e7gz.booking.api.CompletionAttendance;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.loyalty.api.BookingRewards;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.trust.api.BookingOutcomes;

/**
 * {@link CompletionAttendance} on {@code attendance.attendance_record} (E7-61): the completion default through
 * {@link AttendanceDefaults} - the one function every outcome resolves with - then, for an Online Booking, the Trust
 * outcome through {@link BookingOutcomes}, the one writer of booking outcomes, and its Loyalty reconciliation, as the
 * no-show writes them (E7-59). The database checks the whole at commit: the result against the evidence and the status
 * ({@code attendance.assert_attendance_row}), the Trust outcome against the result and its resolution instant
 * ({@code trust.assert_booking_outcome_event}) and the reward against the outcome ({@code loyalty.assert_booking}).
 *
 * <p>The result is resolved at the command instant: the database requires the Trust outcome's {@code occurredAt} to be
 * the shared command instant ({@code trust.prepare_trust_event_insert}) and to equal the attendance's
 * {@code resolved_at}. The booking itself is dated its stored {@code endAt} by the caller; replay orders the outcome by
 * its {@code effectiveAt}, the booking's {@code startAt} (FR-TRS-05), so a late completion has the on-time effect.
 *
 * <p>Points follow from the outcome alone ({@code loyalty.reconcile_booking}): {@code BOOKING_ATTENDED} earns the
 * attended reward, {@code BOOKING_ATTENDANCE_UNCONFIRMED} nothing (FR-ATT-03). The points notification is E7-66's.
 */
@Component
class JdbcCompletionAttendance implements CompletionAttendance {

    private final AttendanceStore store;
    private final BookingOutcomes trust;
    private final BookingRewards rewards;
    private final Outbox outbox;

    JdbcCompletionAttendance(AttendanceStore store, BookingOutcomes trust, BookingRewards rewards, Outbox outbox) {
        this.store = store;
        this.trust = trust;
        this.rewards = rewards;
        this.outbox = outbox;
    }

    @Override
    public AttendanceResult resolveAtCompletion(CommandContext command, Booking booking) {
        Objects.requireNonNull(booking, "booking");
        if (booking.status() != BookingStatus.COMPLETED) {
            throw new IllegalArgumentException("Booking " + booking.bookingId() + " is " + booking.status()
                    + ", not COMPLETED");
        }
        UUID bookingId = booking.bookingId();
        AttendanceStore.Evidence evidence = store.find(command, bookingId)
                .orElseGet(() -> AttendanceStore.Evidence.none(bookingId));
        // FR-ATT-09: a stored result is never re-derived. An ACTIVE booking has none - a no-show leaves ACTIVE, and a
        // correction needs an original outcome, written only when the booking ends - so one here is a defect
        if (evidence.result() != null) {
            throw new IllegalStateException("Attendance of booking " + bookingId + " already resolved ("
                    + evidence.result() + ") before its completion");
        }
        AttendanceResult result = AttendanceDefaults.resolve(booking.source(), AttendanceDefaults.Outcome.COMPLETED,
                evidence);
        Instant now = command.commandNow();
        if (!store.resolve(command, bookingId, result, now)) {
            throw new IllegalStateException("Attendance of booking " + bookingId + " already resolved");
        }
        if (booking.source() == BookingSource.ONLINE) {
            trust.recordCompletion(command, booking.customerId(), bookingId, booking.startAt(), result);
            rewards.reconcile(command, booking.customerId(), bookingId);
        }
        outbox.publish(command, AttendanceEvents.resolved(booking, result));
        return result;
    }
}
