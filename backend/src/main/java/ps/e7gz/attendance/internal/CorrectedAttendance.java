package ps.e7gz.attendance.internal;

import java.time.Instant;
import java.util.UUID;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingStatus;

/**
 * The answer of an Attendance correction (E7-70): the booking's unchanged status, the replaced and the new result, the
 * {@code ATTENDANCE_CORRECTED} Trust event and the correction instant.
 */
public record CorrectedAttendance(UUID bookingId, BookingStatus status, AttendanceResult previousResult,
                                  AttendanceResult result, UUID correctionEventId, Instant correctedAt) { }
