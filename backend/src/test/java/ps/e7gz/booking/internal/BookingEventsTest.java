package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ps.e7gz.booking.internal.VenueAnswerStore.Facts;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;

/** E7-80 (PO decision 25 Nov 2026, Q2): {@code BookingCancelled} names what cancelled the booking. */
class BookingEventsTest {

    private static final Instant START = Instant.parse("2026-12-10T18:00:00Z");

    private static Facts booking(BookingSource source) {
        return new Facts(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                source == BookingSource.ONLINE ? UUID.randomUUID() : null, source, BookingStatus.CONFIRMED, START,
                START.plusSeconds(3600), null, false);
    }

    @Test
    void cancelledWithCauseWritesIt() {
        NewOutboxEvent event = BookingEvents.cancelled(booking(BookingSource.ONLINE),
                BookingTerminalReason.VENUE_EMERGENCY_CLOSURE, IntegrationEvents.CAUSE_CASCADE);

        assertThat(event.eventType()).isEqualTo(IntegrationEvents.BOOKING_CANCELLED);
        assertThat(event.payload()).containsEntry(IntegrationEvents.CANCEL_CAUSE, "CASCADE")
                .containsEntry("terminalReason", "VENUE_EMERGENCY_CLOSURE");
    }

    @Test
    void manualCancelledWithCauseWritesItAndSource() {
        NewOutboxEvent event = BookingEvents.manualCancelled(booking(BookingSource.MANUAL),
                BookingTerminalReason.RESOURCE_EMERGENCY_OUTAGE, IntegrationEvents.CAUSE_START_TIME);

        assertThat(event.payload()).containsEntry(IntegrationEvents.CANCEL_CAUSE, "START_TIME")
                .containsEntry("source", "MANUAL");
    }

    @Test
    void cancelledWithoutCauseOmitsIt() {
        NewOutboxEvent event = BookingEvents.cancelled(booking(BookingSource.ONLINE),
                BookingTerminalReason.CUSTOMER_CANCELLED);

        assertThat(event.payload()).doesNotContainKey(IntegrationEvents.CANCEL_CAUSE);
    }
}
