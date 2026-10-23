package ps.e7gz.attendance.internal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import ps.e7gz.booking.api.AttendanceBookings.Booking;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;

/**
 * The attendance integration events (arch 08 section 3.2). O13 (E7-59, PO decision 12 Oct 2026): one per action,
 * {@code CheckInRecorded}, {@code LatePresentRecorded} or {@code NoShowRecorded}, written in the action's own command
 * transaction - never {@code BookingNoShowRecorded} (one fact, one event) - each with the evidence instant
 * {@code recordedAt}. O12 (E7-61, PO decision 12 Oct 2026): {@code AttendanceResolved} with the {@code attendanceResult}
 * the completion resolved, in the completion transaction. Each carries the fields of {@code BookingCreated} -
 * {@code bookingId, customerId, venueId, resourceId, startAt, endAt} - on aggregate {@code BOOKING} with the Booking ID,
 * so every consumer deep-links to the same booking (FR-NTF-04); a Manual Booking's also carries {@code source} MANUAL
 * and a null {@code customerId}, as every Manual Booking event does (E7-48, D6). The outbox adds {@code correlationId}
 * and {@code occurredAt}. Instants are ISO-8601 UTC strings.
 */
final class AttendanceEvents {

    static final String BOOKING_AGGREGATE = "BOOKING";

    private AttendanceEvents() {
    }

    static NewOutboxEvent recorded(AttendanceAction action, Booking booking, Instant at) {
        Map<String, Object> payload = base(booking);
        payload.put("recordedAt", at.toString());
        return new NewOutboxEvent(action.integrationEvent(), BOOKING_AGGREGATE, booking.bookingId(), payload);
    }

    /** {@code AttendanceResolved} at the booking's completion (E7-61). */
    static NewOutboxEvent resolved(Booking booking, AttendanceResult result) {
        Map<String, Object> payload = base(booking);
        payload.put("attendanceResult", result.name());
        if (booking.source() == BookingSource.MANUAL) {
            payload.put("source", BookingSource.MANUAL.name());
        }
        return new NewOutboxEvent(IntegrationEvents.ATTENDANCE_RESOLVED, BOOKING_AGGREGATE, booking.bookingId(),
                payload);
    }

    private static Map<String, Object> base(Booking booking) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", booking.bookingId());
        payload.put("customerId", booking.customerId());
        payload.put("venueId", booking.venueId());
        payload.put("resourceId", booking.resourceId());
        payload.put("startAt", booking.startAt().toString());
        payload.put("endAt", booking.endAt().toString());
        return payload;
    }
}
