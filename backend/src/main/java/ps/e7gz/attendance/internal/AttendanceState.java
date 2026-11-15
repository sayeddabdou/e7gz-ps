package ps.e7gz.attendance.internal;

import java.time.Instant;
import java.util.UUID;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;

/**
 * A booking's attendance after a Platform Admin attendance action (E7-70; FR-ATT-07), read in the transaction of that
 * action: its status and Booking Reason Code, the stored Attendance Result (null while unresolved) and the evidence
 * that stands - reversed evidence is null, as in {@code VenueBooking.attendance}.
 */
public record AttendanceState(UUID bookingId, BookingStatus status, BookingTerminalReason terminalReason,
                              AttendanceResult result, Instant checkedInAt, Instant latePresentAt, Instant noShowAt) { }
