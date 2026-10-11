package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.identity.api.CustomerContacts;
import ps.e7gz.support.boundary.CommandInstants;
import ps.e7gz.venue.api.VenueAccountVenues;

/**
 * PO decision 11 Oct 2026, E7-49, D3 amendment: the venue's booking search resolves {@code q} as a username with exactly
 * one Identity lookup per search - never a lookup per customer of the venue - and hands the one match to the query.
 */
class VenueBookingSearchTest {

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID VENUE = UUID.randomUUID();

    private final CommandRunner runner = mock(CommandRunner.class);
    private final VenueAnswerStore store = mock(VenueAnswerStore.class);
    private final CustomerContacts customers = mock(CustomerContacts.class);
    private final VenueAccountVenues accounts = mock(VenueAccountVenues.class);
    private final VenueBookings bookings = new VenueBookings(runner, store, customers, accounts,
            mock(BookingHistoryStore.class));

    @BeforeEach
    void wiring() {
        when(accounts.venueOf(ACCOUNT)).thenReturn(VENUE);
        when(runner.run(any())).thenAnswer(call -> call.<Command<?>>getArgument(0)
                .execute(CommandInstants.at(Instant.parse("2026-10-12T18:00:00Z"))));
        when(store.page(any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());
    }

    @Test
    void aSearchMakesExactlyOneUsernameLookupAndPassesItsMatchToTheQuery() {
        UUID customer = UUID.randomUUID();
        when(customers.findByUsername(any(), eq(" omar_k "))).thenReturn(Optional.of(customer));

        bookings.list(ACCOUNT, filter(" omar_k "), null, null);

        verify(customers, times(1)).findByUsername(any(), any());
        verify(customers, never()).find(any(), any());
        verify(store).page(any(), eq(VENUE), any(), eq(customer), isNull(), eq(21));
    }

    @Test
    void anUnknownUsernameStillSearchesTheOtherFieldsWithNoCustomer() {
        when(customers.findByUsername(any(), any())).thenReturn(Optional.empty());

        bookings.list(ACCOUNT, filter("Room 3"), null, 5);

        verify(customers, times(1)).findByUsername(any(), eq("Room 3"));
        verify(store).page(any(), eq(VENUE), any(), isNull(), isNull(), eq(6));
    }

    /**
     * The username lookup gets {@code q} exactly as sent - tabs included - so Identity applies username login's own
     * normalisation (btrim: spaces only); the booking search never trims it first.
     */
    @Test
    void theUsernameLookupGetsQExactlyAsSentTabsIncluded() {
        when(customers.findByUsername(any(), any())).thenReturn(Optional.empty());

        bookings.list(ACCOUNT, filter("\tomar_k "), null, null);

        verify(customers, times(1)).findByUsername(any(), eq("\tomar_k "));
    }

    @Test
    void noSearchMakesNoLookup() {
        bookings.list(ACCOUNT, filter(null), null, null);
        bookings.list(ACCOUNT, filter("   "), null, null);

        verify(customers, never()).findByUsername(any(), any());
        assertThat(filter("   ").q()).isNull();
    }

    private static VenueBookingFilter filter(String q) {
        return new VenueBookingFilter(null, null, null, null, null, null, q);
    }
}
