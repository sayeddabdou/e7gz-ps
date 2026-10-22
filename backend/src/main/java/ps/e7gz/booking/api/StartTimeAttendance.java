package ps.e7gz.booking.api;

import java.time.Instant;
import java.util.UUID;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.command.CommandContext;

/**
 * The attendance side of a start-time cancellation (E7-60; Spec v5 section 31.3: every start-time failure has the
 * Attendance Result NOT_APPLICABLE), declared here because Attendance already depends on Booking
 * ({@link AttendanceBookings}) and owns {@code attendance.attendance_record}; Attendance implements it (CLAUDE.md
 * section 3: a port in the caller's {@code api}, no module cycle).
 */
public interface StartTimeAttendance {

    /**
     * Resolves the booking's Attendance Result by its default for a booking cancelled before attendance -
     * NOT_APPLICABLE (arch 07 section 7.3 rules 5 and 6) - at {@code at}, on the caller's transaction, which has just
     * written the CANCELLED transition. A record already resolved is a defect: it throws and the command rolls back.
     */
    void resolveCancelledAtStart(CommandContext command, UUID bookingId, BookingSource source, Instant at);
}
