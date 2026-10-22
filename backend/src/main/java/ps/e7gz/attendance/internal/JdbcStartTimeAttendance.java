package ps.e7gz.attendance.internal;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.StartTimeAttendance;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.command.CommandContext;

/**
 * {@link StartTimeAttendance} on {@code attendance.attendance_record}: the default of a booking cancelled before
 * attendance through {@link AttendanceDefaults} - the same function the delayed-start no-show resolves it with
 * (E7-59) - so the result is never chosen twice. {@code attendance.assert_attendance_row} checks it at commit.
 */
@Component
class JdbcStartTimeAttendance implements StartTimeAttendance {

    private final AttendanceStore store;

    JdbcStartTimeAttendance(AttendanceStore store) {
        this.store = store;
    }

    @Override
    public void resolveCancelledAtStart(CommandContext command, UUID bookingId, BookingSource source, Instant at) {
        AttendanceStore.Evidence evidence = store.find(command, bookingId)
                .orElseGet(() -> AttendanceStore.Evidence.none(bookingId));
        AttendanceResult result = AttendanceDefaults.resolve(source, AttendanceDefaults.Outcome.CANCELLED_OR_REJECTED,
                evidence);
        if (!store.resolve(command, bookingId, result, at)) {
            throw new IllegalStateException("Attendance of booking " + bookingId + " already resolved");
        }
    }
}
