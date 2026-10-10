package ps.e7gz.booking.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import ps.e7gz.booking.internal.VenueAnswerStore.Facts;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;

/**
 * The booking lifecycle integration events of E7-47 (arch 08 section 3.2; PO decision D4 10 Oct 2026), written in the
 * transaction of their transition. Each carries the fields of {@code BookingCreated} - {@code bookingId, customerId,
 * venueId, resourceId, startAt, endAt} - plus what the transition adds; the outbox adds {@code correlationId} and
 * {@code occurredAt}. Instants are ISO-8601 UTC strings, as in {@code BookingCreated}.
 */
final class BookingEvents {

    private BookingEvents() {
    }

    /** {@code BookingConfirmed}: PENDING -> CONFIRMED by the venue's Accept. */
    static NewOutboxEvent confirmed(Facts booking) {
        return event(IntegrationEvents.BOOKING_CONFIRMED, booking, base(booking));
    }

    /** {@code BookingRejected} with {@code terminalReason} VENUE_REJECTED or REJECTED_AFTER_EXPIRY; no reason text. */
    static NewOutboxEvent rejected(Facts booking, BookingTerminalReason reason) {
        Map<String, Object> payload = base(booking);
        payload.put("terminalReason", reason.name());
        return event(IntegrationEvents.BOOKING_REJECTED, booking, payload);
    }

    /** {@code VenueResponseExpired} with the stored deadline it expired at (FR-BOK-18). */
    static NewOutboxEvent venueResponseExpired(Facts booking) {
        Map<String, Object> payload = base(booking);
        payload.put("venueResponseExpiresAt", booking.venueResponseExpiresAt().toString());
        return event(IntegrationEvents.VENUE_RESPONSE_EXPIRED, booking, payload);
    }

    /** {@code BookingCancelled} with its one {@code terminalReason} (FR-BOK-16's operational cause in E7-47). */
    static NewOutboxEvent cancelled(Facts booking, BookingTerminalReason reason) {
        Map<String, Object> payload = base(booking);
        payload.put("terminalReason", reason.name());
        return event(IntegrationEvents.BOOKING_CANCELLED, booking, payload);
    }

    private static Map<String, Object> base(Facts booking) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", booking.bookingId());
        payload.put("customerId", booking.customerId());
        payload.put("venueId", booking.venueId());
        payload.put("resourceId", booking.resourceId());
        payload.put("startAt", booking.startAt().toString());
        payload.put("endAt", booking.endAt().toString());
        return payload;
    }

    private static NewOutboxEvent event(String type, Facts booking, Map<String, Object> payload) {
        return new NewOutboxEvent(type, CreateOnlineBookingCommand.BOOKING_AGGREGATE, booking.bookingId(), payload);
    }
}
