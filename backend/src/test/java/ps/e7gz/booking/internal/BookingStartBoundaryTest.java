package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ps.e7gz.booking.api.StartTimeAttendance;
import ps.e7gz.booking.internal.VenueAnswerStore.Facts;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.inventory.ledger.api.ActiveReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.support.boundary.Boundary;
import ps.e7gz.support.boundary.CommandInstants;

/**
 * T4, the booking start, at its due instant {@code booking.start_at} and one second either side (arch 08 sections 1
 * and 9; FR-BOK-24, FR-AVL-15; E7-45 boundary pack, moved here from {@code support.boundary.pending} by E7-60).
 * {@code start_at} is an immutable snapshot ({@code tr_10_booking_historical_snapshot_immutable}), so the boundary is hit
 * with the command instant set exactly ({@link CommandInstants}); {@link BookingStartIntegrationTest} runs the same
 * decision through the real worker and database.
 */
class BookingStartBoundaryTest {

    private static final Instant START = Instant.parse("2026-10-18T18:00:00Z");
    private static final Instant END = START.plusSeconds(3600);
    private static final UUID BOOKING = UUID.randomUUID();
    private static final UUID RESOURCE = UUID.randomUUID();

    private final VenueAnswerStore store = mock(VenueAnswerStore.class);
    private final StartTimeResolution resolution = mock(StartTimeResolution.class);
    private final StartTimeCancellation cancellation = mock(StartTimeCancellation.class);
    private final ReservationLedger ledger = mock(ReservationLedger.class);
    private final ScheduledJobs jobs = mock(ScheduledJobs.class);
    private final Outbox outbox = mock(Outbox.class);
    private final BookingStarts starts = new BookingStarts(store, resolution, cancellation,
            mock(StartTimeAttendance.class), ledger, jobs, outbox);
    private CorrelationId.Scope correlation;

    @BeforeEach
    void confirmedBooking() {
        correlation = CorrelationId.open(CorrelationId.generate());
        when(store.facts(any(), eq(BOOKING))).thenReturn(Optional.of(new Facts(BOOKING, UUID.randomUUID(), RESOURCE,
                UUID.randomUUID(), BookingSource.ONLINE, BookingStatus.CONFIRMED, START, END, null, false)));
        when(resolution.resolve(any(), any())).thenReturn(new StartTimeResolution.Outcome.Start());
        when(ledger.activeOf(any(), any()))
                .thenReturn(Optional.of(new ActiveReservation(UUID.randomUUID(), RESOURCE, START, END)));
        when(store.start(any(), eq(BOOKING))).thenReturn(true);
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    /**
     * One second before start_at the handler reschedules to start_at and decides nothing; at and after it, it
     * revalidates as of start_at and the start is dated start_at, whenever the fire ran.
     */
    @ParameterizedTest
    @EnumSource(Boundary.class)
    void t4RevalidatesAtTheStartAndNotASecondBefore(Boundary side) {
        CommandContext context = CommandInstants.at(side, START);

        JobOutcome outcome = starts.start(context, job());

        if (side.beforeDeadline()) {
            assertThat(outcome).isEqualTo(JobOutcome.rescheduleAt(START));
            verifyNoInteractions(resolution, outbox, jobs);
            verify(store, never()).start(any(), any());
        } else {
            assertThat(outcome).isInstanceOf(JobOutcome.Done.class);
            verify(resolution).resolve(context, store.facts(context, BOOKING).orElseThrow());
            verify(store).start(context, BOOKING);
            verify(store).insertTransition(eq(context), eq(BOOKING), eq(START), eq(BookingStatus.CONFIRMED),
                    eq(BookingStatus.ACTIVE), isNull(), eq(ActorType.SYSTEM), isNull(), any());
        }
    }

    private static ScheduledJob job() {
        return new ScheduledJob(UUID.randomUUID(), VenueAnswers.BOOKING_START,
                CreateOnlineBookingCommand.BOOKING_SUBJECT, BOOKING, START, 1, "BOOKING_START:" + BOOKING,
                CorrelationId.generate());
    }
}
