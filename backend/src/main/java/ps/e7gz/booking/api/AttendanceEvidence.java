package ps.e7gz.booking.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.command.CommandContext;

/**
 * The attendance of bookings, for Booking's own projections (Sprint 3 contract, E7-54: {@code VenueBooking.attendance}
 * and {@code VenueBooking.attendanceResult}). Declared here and implemented by the Attendance module, which owns
 * {@code attendance.attendance_record} (arch 03 section 13), so Booking reads that table only through it and never
 * depends on that module (E7-59).
 */
public interface AttendanceEvidence {

    /**
     * The check-in window's length (FR-ATT-02, arch 07 section 7.1): check-in in {@code [startAt, startAt + 15 min)},
     * Mark Present - Late and no-show in {@code [startAt + 15 min, endAt)}, both half-open. The one place the
     * application holds it; {@code attendance.validate_attendance_action_write} holds the same interval.
     */
    Duration CHECK_IN_WINDOW = Duration.ofMinutes(15);

    /**
     * The attendance record of each booking, in one query on the command's transaction - a page of bookings costs one
     * read, not one per row. A booking without a record (no action yet) is absent.
     */
    Map<UUID, Recorded> of(CommandContext command, Collection<UUID> bookingIds);

    /**
     * One booking's attendance. Each evidence instant is null when it was never recorded <b>or</b> a Platform Admin
     * reversed it (Sprint 3 contract: "evidence reversed by an admin is absent", FR-ATT-11).
     *
     * @param result the stored Attendance Result (FR-ATT-07), null until resolved
     */
    record Recorded(AttendanceResult result, Instant checkedInAt, Instant latePresentAt, Instant noShowAt) {
    }
}
