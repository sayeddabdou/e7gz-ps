package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import ps.e7gz.admin.api.AdminAlertKind;
import ps.e7gz.admin.api.AdminAlerts;
import ps.e7gz.booking.api.CompletionAttendance;
import ps.e7gz.booking.internal.VenueAnswerStore.Facts;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.inventory.ledger.api.ActiveReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.support.boundary.Boundary;
import ps.e7gz.support.boundary.CommandInstants;

/**
 * E7-61, the decision of T5 ({@link BookingCompletions}) on stubbed collaborators: superseded for every status but
 * ACTIVE, rescheduled to {@code endAt} before it, and otherwise the completion dated {@code endAt} in its write order -
 * the transition and its history row before the release, the event and the attendance (Loyalty dates the reward from
 * that history row). The database side is {@link BookingCompletionIntegrationTest}'s.
 */
class BookingCompletionsTest {

    private static final Instant START = Instant.parse("2026-10-23T18:00:00Z");
    private static final Instant END = START.plusSeconds(3600);
    private static final UUID BOOKING = UUID.randomUUID();
    private static final UUID RESOURCE = UUID.randomUUID();
    private static final UUID RESERVATION = UUID.randomUUID();

    private final VenueAnswerStore store = mock(VenueAnswerStore.class);
    private final CompletionAttendance attendance = mock(CompletionAttendance.class);
    private final ReservationLedger ledger = mock(ReservationLedger.class);
    private final Outbox outbox = mock(Outbox.class);
    private final AdminAlerts alerts = mock(AdminAlerts.class);
    private final BookingCompletions completions = new BookingCompletions(store, attendance, ledger, outbox, alerts);
    private CorrelationId.Scope correlation;

    @BeforeEach
    void activeBooking() {
        correlation = CorrelationId.open(CorrelationId.generate());
        stub(BookingStatus.ACTIVE, BookingSource.ONLINE);
        when(ledger.activeOf(any(), any()))
                .thenReturn(Optional.of(new ActiveReservation(RESERVATION, RESOURCE, START, END)));
        when(store.complete(any(), eq(BOOKING), any())).thenReturn(true);
        when(ledger.release(any(), eq(RESERVATION), any(), any())).thenReturn(true);
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    /** Arch 07 section 7.3, E7-48 carry-forward: a booking no longer ACTIVE is never mutated. */
    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void aBookingNoLongerActiveIsSupersededWithoutAWrite(BookingStatus status) {
        stub(status, BookingSource.ONLINE);

        JobOutcome outcome = completions.complete(CommandInstants.at(END.plusSeconds(60)), job());

        assertThat(outcome).isInstanceOf(JobOutcome.Superseded.class);
        assertNoWrite();
    }

    @Test
    void aMissingBookingIsSupersededWithoutAWrite() {
        when(store.facts(any(), eq(BOOKING))).thenReturn(Optional.empty());

        assertThat(completions.complete(CommandInstants.at(END), job())).isInstanceOf(JobOutcome.Superseded.class);
        assertNoWrite();
    }

    /** FR-AVL-15: before the stored endAt the fire changes nothing and waits for endAt. */
    @Test
    void beforeTheEndTheJobIsRescheduledToTheEnd() {
        JobOutcome outcome = completions.complete(CommandInstants.at(END.minusSeconds(1)), job());

        assertThat(outcome).isEqualTo(JobOutcome.rescheduleAt(END));
        assertNoWrite();
    }

    /**
     * The exact second (E7-45 boundary pack, {@link CommandInstants}): one second before endAt the fire reschedules to
     * endAt and writes nothing; at endAt and one second after it, it completes, dated endAt. These are the exact
     * instants; a real worker's own instant is never exact, so {@code BookingCompletionBoundaryTest} runs before and
     * at-or-after the end through the real worker and database without claiming one.
     */
    @ParameterizedTest
    @EnumSource(Boundary.class)
    void t5DecidesOnTheExactSecondOfTheEnd(Boundary side) {
        CommandContext context = CommandInstants.at(side, END);

        JobOutcome outcome = completions.complete(context, job());

        if (side.beforeDeadline()) {
            assertThat(outcome).isEqualTo(JobOutcome.rescheduleAt(END));
            assertNoWrite();
        } else {
            assertThat(outcome).isInstanceOf(JobOutcome.Done.class);
            verify(store).complete(context, BOOKING, END);
            verify(ledger).release(context, RESERVATION, END, "COMPLETED");
        }
    }

    /** An Online completion hours late: every booking write dated endAt, in the order the database needs. */
    @Test
    void anOnlineCompletionIsDatedAtTheEndInItsWriteOrder() {
        CommandContext context = CommandInstants.at(END.plusSeconds(5 * 3600));

        assertThat(completions.complete(context, job())).isInstanceOf(JobOutcome.Done.class);

        InOrder order = inOrder(store, ledger, outbox, attendance);
        order.verify(store).complete(context, BOOKING, END);
        order.verify(store).insertTransition(eq(context), eq(BOOKING), eq(END), eq(BookingStatus.ACTIVE),
                eq(BookingStatus.COMPLETED), isNull(), eq(ActorType.SYSTEM), isNull(), any());
        order.verify(ledger).release(context, RESERVATION, END, "COMPLETED");
        order.verify(outbox).publish(eq(context), argThat(event ->
                event.eventType().equals(IntegrationEvents.BOOKING_COMPLETED)
                        && !event.payload().containsKey("source")));
        order.verify(attendance).resolveAtCompletion(eq(context), argThat(booking ->
                booking.status() == BookingStatus.COMPLETED && booking.source() == BookingSource.ONLINE
                        && booking.startAt().equals(START) && booking.endAt().equals(END)));
    }

    /** A Manual Booking completes the same way; its event carries {@code source: MANUAL}. */
    @Test
    void aManualCompletionPublishesTheManualEvent() {
        stub(BookingStatus.ACTIVE, BookingSource.MANUAL);

        assertThat(completions.complete(CommandInstants.at(END), job())).isInstanceOf(JobOutcome.Done.class);

        verify(outbox).publish(any(), argThat(event -> event.eventType().equals(IntegrationEvents.BOOKING_COMPLETED)
                && "MANUAL".equals(event.payload().get("source"))));
        verify(attendance).resolveAtCompletion(any(), argThat(booking -> booking.source() == BookingSource.MANUAL));
    }

    /**
     * D11 (E7-47): a reservation that does not release is a data defect - the fire throws, so the worker rolls back the
     * transition and its history row it wrote, publishes nothing, resolves no attendance (no Trust outcome, no Loyalty
     * effect) and backs the job off; one SEV_1 booking integrity alert is raised after the rollback.
     */
    @Test
    void aReservationThatDoesNotReleaseFailsTheCompletion() {
        when(ledger.release(any(), eq(RESERVATION), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> completions.complete(CommandInstants.at(END), job()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("integrity failure")
                .hasMessageContaining("not released");

        verifyNoInteractions(outbox, attendance);
        verify(alerts).raiseAfterRollback(argThat(alert -> alert.kind() == AdminAlertKind.BOOKING_INTEGRITY_FAILURE
                && alert.subjectId().equals(BOOKING)));
    }

    private void stub(BookingStatus status, BookingSource source) {
        when(store.facts(any(), eq(BOOKING))).thenReturn(Optional.of(new Facts(BOOKING, UUID.randomUUID(), RESOURCE,
                source == BookingSource.MANUAL ? null : UUID.randomUUID(), source, status, START, END, null, false)));
    }

    private void assertNoWrite() {
        verify(store, never()).complete(any(), any(), any());
        verify(store, never()).insertTransition(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(ledger, never()).release(any(), any(), any(), any());
        verifyNoInteractions(outbox, attendance);
    }

    private static ScheduledJob job() {
        return new ScheduledJob(UUID.randomUUID(), ManualBookingCommand.BOOKING_COMPLETE,
                CreateOnlineBookingCommand.BOOKING_SUBJECT, BOOKING, END, 1, "BOOKING_COMPLETE:" + BOOKING,
                CorrelationId.generate());
    }
}
