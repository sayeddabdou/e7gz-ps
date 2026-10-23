package ps.e7gz.attendance.internal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingStatus;

/**
 * A booking's attendance after an action (FR-ATT-07): its status (NO_SHOW after a no-show, otherwise ACTIVE), its
 * stored Attendance Result, and the evidence that stands - each instant null when not recorded or reversed by a
 * Platform Admin.
 */
public record RecordedAttendance(UUID bookingId, BookingStatus status, AttendanceResult attendanceResult,
                                 Instant checkedInAt, Instant latePresentAt, Instant noShowAt) {

    public RecordedAttendance {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(status, "status");
    }
}
