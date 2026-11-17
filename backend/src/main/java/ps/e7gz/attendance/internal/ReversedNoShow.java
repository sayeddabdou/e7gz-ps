package ps.e7gz.attendance.internal;

import java.time.Instant;
import java.util.UUID;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingStatus;

/**
 * The answer of a no-show reversal (E7-70): the status it moved the booking to (CANCELLED before the original endAt,
 * COMPLETED at or after it), the resolved result, the {@code NO_SHOW_REVERSED} Trust event, the
 * {@code ATTENDANCE_CORRECTED} written with it (null for the post-endAt default ATTENDANCE_UNCONFIRMED), the fee
 * decision and the reversal instant.
 */
public record ReversedNoShow(UUID bookingId, BookingStatus status, AttendanceResult result, UUID reversalEventId,
                             UUID correctionEventId, UUID feeDecisionId, Instant reversedAt) { }
