package ps.e7gz.booking.api;

import ps.e7gz.booking.api.AttendanceBookings.Booking;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.command.CommandContext;

/**
 * The attendance side of T5, the booking completion (E7-61; arch 07 section 7.3, FR-ATT-07, FR-ATT-08), declared here
 * because Attendance already depends on Booking ({@link AttendanceBookings}) and owns
 * {@code attendance.attendance_record}; Attendance implements it (CLAUDE.md section 3: a port in the caller's
 * {@code api}, no module cycle), as it does {@link StartTimeAttendance}.
 */
public interface CompletionAttendance {

    /**
     * Resolves the booking's Attendance Result by its default for a completion - ATTENDED after a valid check-in,
     * ATTENDANCE_UNCONFIRMED otherwise, NOT_APPLICABLE for a Manual Booking (rules 1, 2 and 5) - at the command instant,
     * on the caller's transaction, which has just written the ACTIVE -> COMPLETED transition and its history row. For an
     * Online Booking it also writes the booking's Trust outcome and its Loyalty reconciliation, which the database
     * requires in the same transaction; a Manual Booking has neither (FR-MAN-06). Publishes {@code AttendanceResolved}.
     * A record already resolved is never overwritten: it is a defect, so it throws and the command rolls back.
     *
     * @param booking the booking as completed ({@code status} COMPLETED)
     * @return the result stored
     */
    AttendanceResult resolveAtCompletion(CommandContext command, Booking booking);
}
