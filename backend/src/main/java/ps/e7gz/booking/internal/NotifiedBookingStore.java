package ps.e7gz.booking.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.notification.api.NotifiedBookings;
import ps.e7gz.venue.api.VenueContacts;

/**
 * {@link NotifiedBookings} for the booking notifications (E7-55): the facts the venue's answer and T1 read
 * ({@link VenueAnswerStore#FACTS}) and the venue's name. Read-only.
 */
@Component
class NotifiedBookingStore implements NotifiedBookings {

    private final VenueAnswerStore store;
    private final VenueContacts venues;

    NotifiedBookingStore(VenueAnswerStore store, VenueContacts venues) {
        this.store = store;
        this.venues = venues;
    }

    @Override
    public Optional<NotifiedBooking> find(CommandContext command, UUID bookingId) {
        return store.facts(command, bookingId).map(facts -> new NotifiedBooking(facts.bookingId(), facts.customerId(),
                facts.venueId(), venueName(command, facts.venueId()), facts.resourceId(), facts.source(),
                facts.status(), facts.startAt(), facts.venueResponseExpiresAt(), facts.responsePaused()));
    }

    private String venueName(CommandContext command, UUID venueId) {
        return venues.find(command, venueId).map(VenueContacts.Contact::name)
                .orElseThrow(() -> new IllegalStateException("Venue " + venueId + " of a booking does not exist"));
    }
}
