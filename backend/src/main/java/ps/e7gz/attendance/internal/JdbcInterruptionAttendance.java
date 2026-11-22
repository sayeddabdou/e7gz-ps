package ps.e7gz.attendance.internal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.AttendanceBookings.Booking;
import ps.e7gz.booking.api.InterruptionAttendance;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.trust.api.BookingOutcomes;

/**
 * {@link InterruptionAttendance} on {@code attendance.attendance_record} (E7-82): the interruption default through
 * {@link AttendanceDefaults} (rules 4, 7, 8 and 5) - the one function every outcome resolves with - then, for
 * ATTENDED_INTERRUPTED of an Online Booking, the Trust outcome through {@link BookingOutcomes#recordInterruption}, the one
 * writer of booking outcomes (E7-69 carry-forward: this is its first caller). The database checks the whole at commit:
 * the result against the evidence and the operational reason ({@code attendance.assert_attendance_row}) and the Trust
 * outcome against the result and its resolution instant ({@code trust.assert_booking_outcome_event}).
 */
@Component
class JdbcInterruptionAttendance implements InterruptionAttendance {

    private final AttendanceStore store;
    private final BookingOutcomes trust;
    private final Outbox outbox;
    private final AttendanceProtection protection;

    JdbcInterruptionAttendance(AttendanceStore store, BookingOutcomes trust, Outbox outbox,
                               AttendanceProtection protection) {
        this.store = store;
        this.protection = protection;
        this.trust = trust;
        this.outbox = outbox;
    }

    @Override
    public AttendanceResult resolveInterrupted(CommandContext command, Booking booking) {
        Objects.requireNonNull(booking, "booking");
        if (booking.status() != BookingStatus.CANCELLED) {
            throw new IllegalArgumentException("Booking " + booking.bookingId() + " is " + booking.status()
                    + ", not CANCELLED");
        }
        UUID bookingId = booking.bookingId();
        AttendanceStore.Evidence evidence = store.find(command, bookingId)
                .orElseGet(() -> AttendanceStore.Evidence.none(bookingId));
        if (evidence.result() != null) {
            throw new IllegalStateException("Attendance of booking " + bookingId + " already resolved ("
                    + evidence.result() + ") before its interruption");
        }
        AttendanceResult result = AttendanceDefaults.resolve(booking.source(), AttendanceDefaults.Outcome.INTERRUPTED,
                evidence);
        Instant now = command.commandNow();
        if (!store.resolve(command, bookingId, result, now)) {
            throw new IllegalStateException("Attendance of booking " + bookingId + " already resolved");
        }
        if (booking.source() == BookingSource.ONLINE && result == AttendanceResult.ATTENDED_INTERRUPTED) {
            trust.recordInterruption(command, booking.customerId(), bookingId, booking.startAt());
        }
        // E7-78, FR-ATT-18: an early termination is protected too; its result stays the interruption model's
        protection.applyAtResolution(command, booking, result);
        outbox.publish(command, AttendanceEvents.resolved(booking, result));
        return result;
    }
}
