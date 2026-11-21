package ps.e7gz.booking.api;

import ps.e7gz.booking.api.AttendanceBookings.Booking;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.command.CommandContext;

/**
 * The attendance side of an operational interruption of an ACTIVE booking (E7-82; FR-ATT-10, arch 07 section 7.3 rules
 * 4, 7 and 8, Spec v5 section 31.7), declared here because Attendance already depends on Booking and owns
 * {@code attendance.attendance_record}; Attendance implements it, as it does {@link CompletionAttendance} (CLAUDE.md
 * section 3: a port in the caller's {@code api}, no module cycle).
 */
public interface InterruptionAttendance {

    /**
     * Resolves the interrupted booking's Attendance Result from its evidence at the command instant - a valid check-in is
     * ATTENDED_INTERRUPTED, Mark Present - Late only is ATTENDANCE_UNCONFIRMED, nothing is NOT_APPLICABLE, a Manual
     * Booking NOT_APPLICABLE - on the caller's transaction, which has just written the ACTIVE -> CANCELLED transition
     * under an operational reason. For ATTENDED_INTERRUPTED of an Online Booking it also appends the canonical Trust
     * outcome {@code BOOKING_ATTENDED_INTERRUPTED} through {@code trust.api.BookingOutcomes#recordInterruption} (E7-69:
     * the counter resets, no Verified Player progress, no points - FR-TRS-11). The caller reconciles that outcome's
     * reward (zero) in its one Loyalty context ({@code loyalty.api.BookingRewards#reconcileAll}); nothing else writes a
     * Trust outcome: the other two results change no counter (FR-ATT-10). Publishes {@code AttendanceResolved}. A record
     * already resolved is a defect: it throws and the command rolls back.
     *
     * @param booking the booking as interrupted ({@code status} CANCELLED)
     * @return the result stored
     */
    AttendanceResult resolveInterrupted(CommandContext command, Booking booking);
}
