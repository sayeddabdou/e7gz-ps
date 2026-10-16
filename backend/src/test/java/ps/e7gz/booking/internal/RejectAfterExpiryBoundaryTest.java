package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ps.e7gz.support.boundary.CommandInstants.at;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ps.e7gz.admin.api.AdminAlerts;
import ps.e7gz.booking.internal.VenueAnswerStore.Facts;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.identity.api.CustomerActivation;
import ps.e7gz.inventory.availability.api.AvailabilityQuery;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.loyalty.api.BookingFeeRefunds;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.operations.api.ResponseTimerPauses;
import ps.e7gz.operations.api.UnresolvedEmergencies;
import ps.e7gz.resource.api.ResourceStates;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.trust.api.ActiveBans;
import ps.e7gz.venue.api.VenueEligibility;

/**
 * FR-BOK-21, PO decision D5: Reject After Expiry only while {@code now < startAt}, to the exact instant. A booking's
 * {@code start_at} is an immutable snapshot ({@code tr_10_booking_historical_snapshot_immutable}) at least 15 minutes
 * after its creation, so the boundary is hit here, with the command instant set exactly, rather than in the database.
 */
class RejectAfterExpiryBoundaryTest {

    private static final Instant START = Instant.parse("2026-10-12T18:00:00Z");
    private static final UUID BOOKING = UUID.randomUUID();
    private static final UUID VENUE = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();

    private final VenueAnswerStore store = mock(VenueAnswerStore.class);
    private final VenueBookings bookings = mock(VenueBookings.class);
    private final ReservationLedger ledger = mock(ReservationLedger.class);
    private final BookingFeeRefunds refunds = mock(BookingFeeRefunds.class);
    private final VenueAnswers answers = new VenueAnswers(store, bookings, mock(VenueEligibility.class),
            mock(ResourceStates.class), mock(UnresolvedEmergencies.class), mock(AvailabilityQuery.class), ledger,
            mock(CustomerActivation.class), mock(ActiveBans.class), mock(ScheduledJobs.class), mock(Outbox.class),
            refunds, mock(ResponseTimerPauses.class), mock(AdminAlerts.class));
    private CorrelationId.Scope correlation;

    @BeforeEach
    void expiredBooking() {
        correlation = CorrelationId.open(CorrelationId.generate());
        when(store.facts(any(), eq(BOOKING))).thenReturn(Optional.of(new Facts(BOOKING, VENUE, UUID.randomUUID(),
                UUID.randomUUID(), BookingSource.ONLINE, BookingStatus.VENUE_RESPONSE_EXPIRED, START,
                START.plusSeconds(3600), START.minusSeconds(1800), false)));
        when(ledger.activeOf(any(), any())).thenReturn(Optional.empty());
        when(store.terminate(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(store.insertTransition(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    @Test
    void oneSecondBeforeTheStartItRejects() {
        answers.rejectAfterExpiry(at(START.minusSeconds(1)), BOOKING, VENUE, ACCOUNT);

        verify(store).terminate(any(), eq(BOOKING), eq(BookingStatus.VENUE_RESPONSE_EXPIRED),
                eq(BookingStatus.REJECTED), eq(BookingTerminalReason.REJECTED_AFTER_EXPIRY),
                eq(START.minusSeconds(1)), eq(null));
    }

    @Test
    void exactlyAtTheStartItIsDeadlinePassed() {
        assertDeadlinePassed(START);
    }

    @Test
    void afterTheStartItIsDeadlinePassed() {
        assertDeadlinePassed(START.plusSeconds(1));
    }

    private void assertDeadlinePassed(Instant now) {
        assertThatThrownBy(() -> answers.rejectAfterExpiry(at(now), BOOKING, VENUE, ACCOUNT))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.DEADLINE_PASSED));
        verify(store, never()).terminate(any(), any(), any(), any(), any(), any(), any());
    }
}
