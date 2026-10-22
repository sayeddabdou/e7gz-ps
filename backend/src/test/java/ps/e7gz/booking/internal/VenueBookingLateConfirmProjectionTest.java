package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ps.e7gz.booking.api.AttendanceEvidence;
import ps.e7gz.booking.api.LateConfirmWindows;
import ps.e7gz.booking.internal.VenueAnswerStore.ViewRow;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.money.Money;
import ps.e7gz.identity.api.CustomerContacts;
import ps.e7gz.support.boundary.CommandInstants;
import ps.e7gz.venue.api.VenueAccountVenues;

/**
 * E7-58 Phase 2C: the venue's booking list takes the Late Confirm deadlines of a whole page in one read through
 * {@link LateConfirmWindows} - never one per row - and shows them, with the pause flag, only on AWAITING bookings.
 */
class VenueBookingLateConfirmProjectionTest {

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID VENUE = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-13T16:50:00Z");

    private final CommandRunner runner = mock(CommandRunner.class);
    private final VenueAnswerStore store = mock(VenueAnswerStore.class);
    private final CustomerContacts customers = mock(CustomerContacts.class);
    private final VenueAccountVenues accounts = mock(VenueAccountVenues.class);
    private final LateConfirmWindows lateConfirms = mock(LateConfirmWindows.class);
    private final VenueBookings bookings = new VenueBookings(runner, store, customers, accounts,
            mock(BookingHistoryStore.class), lateConfirms, mock(AttendanceEvidence.class));

    @BeforeEach
    void wiring() {
        when(accounts.venueOf(ACCOUNT)).thenReturn(VENUE);
        when(runner.run(any())).thenAnswer(call -> call.<Command<?>>getArgument(0)
                .execute(CommandInstants.at(NOW)));
        when(customers.find(any(), any())).thenAnswer(call -> Optional.of(
                new CustomerContacts.Contact(call.getArgument(1), "omar_k", "+201001234567")));
    }

    @Test
    void aPageReadsItsLateConfirmDeadlinesOnceAndShowsThemOnlyWhileAwaiting() {
        ViewRow awaiting = row(BookingStatus.AWAITING_CUSTOMER_CONFIRMATION, false);
        ViewRow paused = row(BookingStatus.AWAITING_CUSTOMER_CONFIRMATION, true);
        ViewRow confirmed = row(BookingStatus.CONFIRMED, false);
        Instant firstDeadline = NOW.plusSeconds(240);
        Instant secondDeadline = NOW.plusSeconds(120);
        when(store.page(any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of(awaiting, paused, confirmed));
        when(lateConfirms.openDeadlines(any(), any())).thenReturn(Map.of(
                awaiting.bookingId(), firstDeadline, paused.bookingId(), secondDeadline));

        List<VenueBookingRow> page = bookings.list(ACCOUNT, new VenueBookingFilter(null, null, null, null, null, null,
                null), null, null).items();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> asked = ArgumentCaptor.forClass(Collection.class);
        verify(lateConfirms, times(1)).openDeadlines(any(), asked.capture());
        assertThat(asked.getValue()).as("only the AWAITING bookings, in one read")
                .containsExactlyInAnyOrder(awaiting.bookingId(), paused.bookingId());
        assertThat(page).extracting(VenueBookingRow::lateConfirmExpiresAt)
                .containsExactly(firstDeadline, secondDeadline, null);
        assertThat(page).extracting(VenueBookingRow::lateConfirmPaused).containsExactly(false, true, false);
    }

    private static ViewRow row(BookingStatus status, boolean lateConfirmPaused) {
        Instant start = NOW.plusSeconds(3600);
        return new ViewRow(UUID.randomUUID(), VENUE, BookingSource.ONLINE, status, null, null, UUID.randomUUID(),
                "Room 1", Activity.BILLIARDS, null, null, start, start.plusSeconds(3600), LocalDate.of(2026, 10, 13),
                60, Money.of("77.77"), null, false, lateConfirmPaused, UUID.randomUUID(), null, null,
                NOW.minusSeconds(1200), new ContactVisibility.Facts(BookingSource.ONLINE, status, start,
                        start.plusSeconds(3600), null, null, null));
    }
}
