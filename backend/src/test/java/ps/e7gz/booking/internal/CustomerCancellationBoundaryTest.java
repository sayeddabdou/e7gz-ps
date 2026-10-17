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

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ps.e7gz.admin.api.AdminAlerts;
import ps.e7gz.booking.internal.VenueAnswerStore.Facts;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.inventory.ledger.api.ActiveReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.loyalty.api.BookingFeeRefunds;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.operations.api.ResponseTimerPauses;
import ps.e7gz.scheduling.api.ScheduledJobs;

/**
 * E7-49 - PO decision 11 Oct 2026, E7-49 (D2), on a controlled command instant: the customer's cancellation decides
 * from the booking's state and {@code now < startAt} to the exact instant (FR-AVL-15, FR-BOK-31). A booking's
 * {@code start_at} is an immutable snapshot at least 15 minutes after its creation, so the boundary is hit here, as in
 * {@link RejectAfterExpiryBoundaryTest}; {@code CustomerCancellationIntegrationTest} runs the same rule on the database.
 */
class CustomerCancellationBoundaryTest {

    private static final Instant START = Instant.parse("2026-10-12T18:00:00Z");
    private static final UUID BOOKING = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID RESOURCE = UUID.randomUUID();

    private final VenueAnswerStore store = mock(VenueAnswerStore.class);
    private final ReservationLedger ledger = mock(ReservationLedger.class);
    private final ScheduledJobs jobs = mock(ScheduledJobs.class);
    private final ResponseTimerPauses pauses = mock(ResponseTimerPauses.class);
    private final CustomerCancellation cancellation = new CustomerCancellation(store, ledger,
            mock(BookingFeeRefunds.class), jobs, mock(Outbox.class), pauses, mock(AdminAlerts.class));
    private CorrelationId.Scope correlation;

    @BeforeEach
    void stores() {
        correlation = CorrelationId.open(CorrelationId.generate());
        when(ledger.activeOf(any(), any())).thenReturn(Optional.of(new ActiveReservation(UUID.randomUUID(), RESOURCE,
                START, START.plus(Duration.ofHours(1)))));
        when(store.terminate(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(store.insertTransition(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());
        when(store.endResponsePause(any(), any(), any())).thenReturn(Optional.of(Duration.ofMinutes(4)));
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"PENDING", "CONFIRMED"})
    void oneSecondBeforeTheStartItCancels(BookingStatus status) {
        booking(status, false);

        cancellation.cancel(at(START.minusSeconds(1)), BOOKING, CUSTOMER);

        verify(store).terminate(any(), eq(BOOKING), eq(status), eq(BookingStatus.CANCELLED),
                eq(BookingTerminalReason.CUSTOMER_CANCELLED), eq(START.minusSeconds(1)), eq(null));
        verify(store).insertTransition(any(), eq(BOOKING), eq(START.minusSeconds(1)), eq(status),
                eq(BookingStatus.CANCELLED), eq(BookingTerminalReason.CUSTOMER_CANCELLED), eq(ActorType.CUSTOMER),
                eq(CUSTOMER), any());
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"PENDING", "CONFIRMED"})
    void exactlyAtAndAfterTheStartItIsDeadlinePassed(BookingStatus status) {
        booking(status, false);

        assertRefused(START, ApiErrorCode.DEADLINE_PASSED);
        assertRefused(START.plusSeconds(1), ApiErrorCode.DEADLINE_PASSED);
    }

    @Test
    void anActiveBookingIsDeadlinePassed() {
        booking(BookingStatus.ACTIVE, false);

        assertRefused(START.plusSeconds(60), ApiErrorCode.DEADLINE_PASSED);
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"VENUE_RESPONSE_EXPIRED", "AWAITING_CUSTOMER_CONFIRMATION",
            "NO_SHOW", "COMPLETED", "REJECTED", "CANCELLED"})
    void everyOtherStatusIsBookingStateChangedBeforeOrAfterTheStart(BookingStatus status) {
        booking(status, false);

        assertRefused(START.minus(Duration.ofHours(1)), ApiErrorCode.BOOKING_STATE_CHANGED);
        assertRefused(START.plus(Duration.ofHours(1)), ApiErrorCode.BOOKING_STATE_CHANGED);
    }

    @Test
    void anotherCustomersBookingIsNotFound() {
        booking(BookingStatus.PENDING, false);

        assertThatThrownBy(() -> cancellation.cancel(at(START.minusSeconds(60)), BOOKING, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ApiErrorCode.NOT_FOUND));
        verify(store, never()).terminate(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aPausedPendingBookingIsCancelledAndItsPauseEndsAtTheSameInstant() {
        booking(BookingStatus.PENDING, true);
        Instant now = START.minus(Duration.ofMinutes(30));

        cancellation.cancel(at(now), BOOKING, CUSTOMER);

        verify(store).endResponsePause(any(), eq(BOOKING), eq(now));
        verify(pauses).closeOpen(any(), eq(BOOKING), eq(now), eq(Duration.ofMinutes(4)));
        verify(store).terminate(any(), eq(BOOKING), eq(BookingStatus.PENDING), eq(BookingStatus.CANCELLED),
                eq(BookingTerminalReason.CUSTOMER_CANCELLED), eq(now), eq(null));
    }

    private void booking(BookingStatus status, boolean paused) {
        when(store.facts(any(), eq(BOOKING))).thenReturn(Optional.of(new Facts(BOOKING, UUID.randomUUID(), RESOURCE,
                CUSTOMER, BookingSource.ONLINE, status, START, START.plus(Duration.ofHours(1)),
                START.minus(Duration.ofMinutes(50)), paused)));
    }

    private void assertRefused(Instant now, ApiErrorCode code) {
        assertThatThrownBy(() -> cancellation.cancel(at(now), BOOKING, CUSTOMER))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
        verify(store, never()).terminate(any(), any(), any(), any(), any(), any(), any());
        verify(ledger, never()).release(any(), any(), any(), any());
    }
}
