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
import ps.e7gz.booking.api.VenueAccountRestrictionsAt;
import ps.e7gz.booking.internal.VenueAnswerStore.Facts;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.loyalty.api.BookingFeeRefunds;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.support.boundary.Boundary;
import ps.e7gz.support.boundary.CommandInstants;

/**
 * T6 {@code VENUE_RESPONSE_EXPIRED} resolution, due at {@code booking.start_at} (arch 08 sections 1 and 9; FR-BOK-23,
 * FR-AVL-15; E7-45 boundary pack, moved here from {@code support.boundary.pending} by E7-118). {@code start_at} is an
 * immutable snapshot, so the boundary is hit with the command instant set exactly ({@link CommandInstants});
 * {@link ExpiredRequestResolutionIntegrationTest} runs the same decision through the real worker and database.
 */
class ExpiredRequestResolutionBoundaryTest {

    private static final Instant START = Instant.parse("2026-11-20T18:00:00Z");
    private static final UUID BOOKING = UUID.randomUUID();
    private static final UUID VENUE = UUID.randomUUID();
    private static final UUID RESOURCE = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID HISTORY = UUID.randomUUID();

    private final VenueAnswerStore store = mock(VenueAnswerStore.class);
    private final StartTimeResolution startTime = mock(StartTimeResolution.class);
    private final VenueAccountRestrictionsAt restrictions = mock(VenueAccountRestrictionsAt.class);
    private final ReservationLedger ledger = mock(ReservationLedger.class);
    private final BookingFeeRefunds refunds = mock(BookingFeeRefunds.class);
    private final ScheduledJobs jobs = mock(ScheduledJobs.class);
    private final Outbox outbox = mock(Outbox.class);
    private final ExpiredRequestResolutions resolutions = new ExpiredRequestResolutions(store, startTime, restrictions,
            ledger, refunds, jobs, outbox, mock(StartTimeCancellation.class));
    private CorrelationId.Scope correlation;

    @BeforeEach
    void expiredRequest() {
        correlation = CorrelationId.open(CorrelationId.generate());
        when(store.facts(any(), eq(BOOKING))).thenReturn(Optional.of(new Facts(BOOKING, VENUE, RESOURCE, CUSTOMER,
                BookingSource.ONLINE, BookingStatus.VENUE_RESPONSE_EXPIRED, START, START.plusSeconds(3600),
                START.minusSeconds(3000), false)));
        when(startTime.operationalCauseAtStart(any(), any())).thenReturn(Optional.empty());
        when(ledger.activeOf(any(), any())).thenReturn(Optional.empty());
        when(store.terminate(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(store.insertTransition(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(HISTORY);
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    /**
     * One second before start_at the job is rescheduled to start_at and nothing is decided or written; at and after it
     * the request is CANCELLED, every write dated start_at whenever the fire ran, and the causes are read as of
     * start_at.
     */
    @ParameterizedTest
    @EnumSource(Boundary.class)
    void t6ResolvesAnExpiredRequestAtItsOriginalStartAndNotASecondBefore(Boundary side) {
        CommandContext context = CommandInstants.at(side, START);

        JobOutcome outcome = resolutions.resolve(context, job());

        if (side.beforeDeadline()) {
            assertThat(outcome).isEqualTo(JobOutcome.rescheduleAt(START));
            verifyNoInteractions(startTime, restrictions, refunds, outbox, jobs);
            verify(store, never()).terminate(any(), any(), any(), any(), any(), any(), any());
        } else {
            assertThat(outcome).isInstanceOf(JobOutcome.Done.class);
            Facts booking = store.facts(context, BOOKING).orElseThrow();
            verify(startTime).operationalCauseAtStart(context, booking);
            verify(restrictions).disabledAt(context, VENUE, START);
            verify(store).terminate(context, BOOKING, BookingStatus.VENUE_RESPONSE_EXPIRED, BookingStatus.CANCELLED,
                    BookingTerminalReason.VENUE_DID_NOT_RESPOND_IN_TIME, START, null);
            verify(store).insertTransition(eq(context), eq(BOOKING), eq(START),
                    eq(BookingStatus.VENUE_RESPONSE_EXPIRED), eq(BookingStatus.CANCELLED),
                    eq(BookingTerminalReason.VENUE_DID_NOT_RESPOND_IN_TIME), eq(ActorType.SYSTEM), isNull(), any());
            verify(refunds).recordDecision(context, CUSTOMER, BOOKING, HISTORY);
            verify(outbox).publish(eq(context), any());
        }
    }

    private static ScheduledJob job() {
        return new ScheduledJob(UUID.randomUUID(), ExpiredRequestResolutions.VENUE_RESPONSE_EXPIRED_RESOLUTION,
                CreateOnlineBookingCommand.BOOKING_SUBJECT, BOOKING, START, 1,
                ExpiredRequestResolutions.resolutionKey(BOOKING), CorrelationId.generate());
    }
}
