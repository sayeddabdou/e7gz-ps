package ps.e7gz.attendance.internal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import ps.e7gz.booking.api.AttendanceBookings.Booking;
import ps.e7gz.messaging.api.NewOutboxEvent;

/**
 * The attendance integration events of O13 (arch 08 section 3.2; E7-59, PO decision 12 Oct 2026): one per action,
 * {@code CheckInRecorded}, {@code LatePresentRecorded} or {@code NoShowRecorded}, written in the action's own command
 * transaction - never {@code BookingNoShowRecorded} (one fact, one event). Each carries the fields of
 * {@code BookingCreated} - {@code bookingId, customerId, venueId, resourceId, startAt, endAt} - plus the evidence
 * instant {@code recordedAt}, on aggregate {@code BOOKING} with the Booking ID, so every consumer deep-links to the
 * same booking (FR-NTF-04). The outbox adds {@code correlationId} and {@code occurredAt}. Instants are ISO-8601 UTC
 * strings.
 */
final class AttendanceEvents {

    static final String BOOKING_AGGREGATE = "BOOKING";

    private AttendanceEvents() {
    }

    static NewOutboxEvent recorded(AttendanceAction action, Booking booking, Instant at) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", booking.bookingId());
        payload.put("customerId", booking.customerId());
        payload.put("venueId", booking.venueId());
        payload.put("resourceId", booking.resourceId());
        payload.put("startAt", booking.startAt().toString());
        payload.put("endAt", booking.endAt().toString());
        payload.put("recordedAt", at.toString());
        return new NewOutboxEvent(action.integrationEvent(), BOOKING_AGGREGATE, booking.bookingId(), payload);
    }
}
