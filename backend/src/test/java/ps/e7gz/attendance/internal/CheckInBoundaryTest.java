package ps.e7gz.attendance.internal;

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
import org.junit.jupiter.params.provider.ValueSource;
import ps.e7gz.admin.api.AdminAlerts;
import ps.e7gz.attendance.internal.AttendanceStore.Evidence;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.booking.api.AttendanceBookings;
import ps.e7gz.booking.api.AttendanceBookings.Booking;
import ps.e7gz.booking.api.AttendanceBookings.DelayedStart;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.inventory.ledger.api.ActiveReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.loyalty.api.BookingRewards;
import ps.e7gz.messaging.api.CommandAnswer;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.support.boundary.Boundary;
import ps.e7gz.support.boundary.BoundaryAssertions;
import ps.e7gz.trust.api.NoShowOutcomes;

/**
 * The check-in window closes 15 minutes after the start (roadmap Phase 3 exit; FRS 4.6, arch 07 section 7). A booking's
 * {@code start_at} is an immutable snapshot, so each boundary is hit here with the command instant set exactly
 * ({@link ps.e7gz.support.boundary.CommandInstants}) on the real rule, its stores mocked; the database guard
 * ({@code attendance.validate_attendance_action_write}) holds the same half-open windows and is exercised by
 * {@code AttendanceIntegrationTest}.
 */
class CheckInBoundaryTest {

    private static final Instant START = Instant.parse("2026-10-12T18:00:00Z");
    private static final Instant LATE_OPENS = START.plus(Duration.ofMinutes(15));
    private static final Instant END = START.plus(Duration.ofMinutes(60));
    private static final UUID BOOKING = UUID.randomUUID();
    private static final UUID VENUE = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID ROOM = UUID.randomUUID();

    private final AttendanceBookings bookings = mock(AttendanceBookings.class);
    private final AttendanceStore store = mock(AttendanceStore.class);
    private final ReservationLedger ledger = mock(ReservationLedger.class);
    private final NoShowOutcomes trust = mock(NoShowOutcomes.class);
    private final BookingRewards rewards = mock(BookingRewards.class);
    private final Outbox outbox = mock(Outbox.class);
    private final AttendanceCommands commands = new AttendanceCommands(bookings, store, ledger, trust, rewards,
            mock(AuditWriter.class), outbox, mock(AdminAlerts.class));
    private CorrelationId.Scope correlation;

    @BeforeEach
    void activeBookingWithoutEvidence() {
        correlation = CorrelationId.open(CorrelationId.generate());
        when(bookings.find(any(), eq(BOOKING))).thenReturn(Optional.of(new Booking(BOOKING, VENUE, ROOM, CUSTOMER,
                BookingSource.ONLINE, BookingStatus.ACTIVE, START, END)));
        // no record before the first action; the command reads it back after its own write
        when(store.find(any(), eq(BOOKING))).thenReturn(Optional.empty(), Optional.of(Evidence.none(BOOKING)));
        when(store.record(any(), any(), any(), any(), any())).thenReturn(true);
        when(bookings.markNoShow(any(), any(), any(), any(), any())).thenReturn(true);
        when(ledger.activeOf(any(), any())).thenReturn(Optional.of(new ActiveReservation(UUID.randomUUID(), ROOM,
                START, END)));
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT14M59S", "PT15M"})
    void checkInAtFourteenFiftyNineSucceedsAndAtFifteenIsRefusedWithLatePresentOffered(String afterStart) {
        // The command instant at startAt + afterStart (CommandInstants on the rule, since start_at is an immutable
        // snapshot): +14:59 checks in; +15:00 is refused and Mark Present - Late becomes available.
        Instant now = START.plus(Duration.parse(afterStart));
        if (now.isBefore(LATE_OPENS)) {
            commands.record(at(now), AttendanceAction.CHECK_IN, BOOKING, VENUE, ACCOUNT);
            verify(store).record(any(), eq(AttendanceAction.CHECK_IN), eq(BOOKING), eq(now), eq(ACCOUNT));
        } else {
            assertRefused(now, AttendanceAction.CHECK_IN, ApiErrorCode.CHECK_IN_WINDOW_CLOSED);
            commands.record(at(now), AttendanceAction.LATE_PRESENT, BOOKING, VENUE, ACCOUNT);
            verify(store).record(any(), eq(AttendanceAction.LATE_PRESENT), eq(BOOKING), eq(now), eq(ACCOUNT));
        }
    }

    @Test
    void aLatePresentBookingCannotBeMarkedNoShow() {
        when(store.find(any(), eq(BOOKING))).thenReturn(Optional.of(new Evidence(BOOKING, null, null, null,
                LATE_OPENS.plusSeconds(60), null, null, null)));

        assertRefused(LATE_OPENS.plusSeconds(120), AttendanceAction.NO_SHOW, ApiErrorCode.NO_SHOW_NOT_AVAILABLE);
        verify(bookings, never()).markNoShow(any(), any(), any(), any(), any());
        verify(ledger, never()).release(any(), any(), any(), any());
        verify(trust, never()).recordNoShow(any(), any(), any(), any());
    }

    /** The check-in window opens exactly at startAt: one second before it is refused, at it and after it permitted. */
    @ParameterizedTest
    @EnumSource(Boundary.class)
    void checkInOpensAtTheStart(Boundary side) {
        assertThat(permitted(side.now(START), AttendanceAction.CHECK_IN)).isEqualTo(!side.beforeDeadline());
    }

    /** ... and closes at startAt + 15:00 (FR-ATT-02): permitted only strictly before it. */
    @ParameterizedTest
    @EnumSource(Boundary.class)
    void checkInClosesAtFifteenMinutes(Boundary side) {
        BoundaryAssertions.assertBoundaryRule(side, permitted(side.now(LATE_OPENS), AttendanceAction.CHECK_IN));
    }

    /** Mark Present - Late and no-show open exactly at startAt + 15:00 (FR-ATT-03, FR-ATT-04). */
    @ParameterizedTest
    @EnumSource(Boundary.class)
    void latePresentAndNoShowOpenAtFifteenMinutes(Boundary side) {
        assertThat(permitted(side.now(LATE_OPENS), AttendanceAction.LATE_PRESENT)).isEqualTo(!side.beforeDeadline());
        assertThat(permitted(side.now(LATE_OPENS), AttendanceAction.NO_SHOW)).isEqualTo(!side.beforeDeadline());
    }

    /** ... and close at endAt: at exactly endAt neither is available. */
    @ParameterizedTest
    @EnumSource(Boundary.class)
    void latePresentAndNoShowCloseAtTheEnd(Boundary side) {
        BoundaryAssertions.assertBoundaryRule(side, permitted(side.now(END), AttendanceAction.LATE_PRESENT));
        BoundaryAssertions.assertBoundaryRule(side, permitted(side.now(END), AttendanceAction.NO_SHOW));
    }

    /** The no-show is dated, and releases the reservation, at the command instant - never at {@code endAt}. */
    @Test
    void aNoShowReleasesAtItsOwnInstant() {
        Instant now = END.minusSeconds(1);
        RecordedAttendance recorded = recorded(now, AttendanceAction.NO_SHOW);

        assertThat(recorded.status()).isEqualTo(BookingStatus.NO_SHOW);
        verify(bookings).markNoShow(any(), eq(BOOKING), eq(BookingStatus.ACTIVE), eq(now), eq(ACCOUNT));
        verify(ledger).release(any(), any(), eq(now), eq("NO_SHOW"));
        verify(trust).recordNoShow(any(), eq(CUSTOMER), eq(BOOKING), eq(START));
        verify(rewards).reconcile(any(), eq(CUSTOMER), eq(BOOKING));
    }

    // ------------- the delayed-start race (Amr Heiba, 17 Oct 2026; PO decision 19 Oct 2026, R1: all three actions)

    /**
     * A delayed start that would have succeeded, behind each of the three actions: Booking starts it first (T4's own
     * transition), then the action is recorded as on any ACTIVE booking - a no-show from ACTIVE, with its release, Trust
     * outcome and reward reconciliation.
     */
    @ParameterizedTest
    @EnumSource(AttendanceAction.class)
    void eachActionBehindADelayedStartThatStartsIsRecordedAsOnAnActiveBooking(AttendanceAction action) {
        confirmed(DelayedStart.STARTED);
        Instant now = windowOpens(action);

        RecordedAttendance recorded = recorded(now, action);

        verify(bookings).resolveDelayedStart(any(), eq(BOOKING));
        verify(store).record(any(), eq(action), eq(BOOKING), eq(now), eq(ACCOUNT));
        if (action == AttendanceAction.NO_SHOW) {
            assertThat(recorded.status()).isEqualTo(BookingStatus.NO_SHOW);
            verify(bookings).markNoShow(any(), eq(BOOKING), eq(BookingStatus.ACTIVE), eq(now), eq(ACCOUNT));
            verify(ledger).release(any(), any(), eq(now), eq("NO_SHOW"));
            verify(trust).recordNoShow(any(), eq(CUSTOMER), eq(BOOKING), eq(START));
            verify(rewards).reconcile(any(), eq(CUSTOMER), eq(BOOKING));
        } else {
            assertThat(recorded.status()).isEqualTo(BookingStatus.ACTIVE);
            verify(bookings, never()).markNoShow(any(), any(), any(), any(), any());
        }
        verify(bookings).recordVenueEvent(any(), eq(BOOKING), eq(now), eq(action.bookingEvent()), eq(ACCOUNT));
    }

    /**
     * A delayed start that would have cancelled, behind each action: a committed 409 BOOKING_STATE_CHANGED, the
     * attendance default NOT_APPLICABLE, and no evidence, transition, Trust outcome, reward or event.
     */
    @ParameterizedTest
    @EnumSource(AttendanceAction.class)
    void eachActionBehindADelayedStartThatWouldCancelIsACommittedRefusalWithoutPenalty(AttendanceAction action) {
        confirmed(DelayedStart.CANCELLED);
        when(store.resolve(any(), any(), any(), any())).thenReturn(true);
        Instant now = windowOpens(action);

        CommandAnswer<RecordedAttendance> answer = commands.record(at(now), action, BOOKING, VENUE, ACCOUNT);

        assertThat(answer).isInstanceOfSatisfying(CommandAnswer.Refused.class, refused ->
                assertThat(refused.refusal().code()).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED));
        verify(store).resolve(any(), eq(BOOKING), eq(AttendanceResult.NOT_APPLICABLE), eq(now));
        assertNoNoShowWritten();
        verify(outbox, never()).publish(any(), any());
    }

    /** No start job waiting, or a start that cannot be resolved: BOOKING_STATE_CHANGED, nothing written - each action. */
    @ParameterizedTest
    @EnumSource(value = DelayedStart.class, names = {"NOT_DELAYED", "UNDETERMINED"})
    void aConfirmedBookingWhoseStartIsNotWaitingOrNotResolvableIsBookingStateChanged(DelayedStart answer) {
        confirmed(answer);

        for (AttendanceAction action : AttendanceAction.values()) {
            assertRefused(windowOpens(action), action, ApiErrorCode.BOOKING_STATE_CHANGED);
        }
        assertNoNoShowWritten();
        verify(store, never()).resolve(any(), any(), any(), any());
    }

    /**
     * Each action's own window decides first for a delayed start too: one second before it, the window's own code and
     * the start is not even resolved - nothing started, cancelled or recorded; at and after, it is resolved.
     */
    @ParameterizedTest
    @EnumSource(Boundary.class)
    void aDelayedStartIsResolvedOnlyInsideTheActionsWindow(Boundary side) {
        confirmed(DelayedStart.CANCELLED);
        when(store.resolve(any(), any(), any(), any())).thenReturn(true);
        Instant now = side.now(LATE_OPENS);

        if (side.beforeDeadline()) {
            assertRefused(now, AttendanceAction.NO_SHOW, ApiErrorCode.NO_SHOW_NOT_AVAILABLE);
            assertRefused(now, AttendanceAction.LATE_PRESENT, ApiErrorCode.LATE_PRESENT_NOT_AVAILABLE);
            verify(bookings, never()).resolveDelayedStart(any(), any());
        } else {
            assertRefused(now, AttendanceAction.CHECK_IN, ApiErrorCode.CHECK_IN_WINDOW_CLOSED);
            assertThat(commands.record(at(now), AttendanceAction.NO_SHOW, BOOKING, VENUE, ACCOUNT))
                    .isInstanceOf(CommandAnswer.Refused.class);
            verify(bookings).resolveDelayedStart(any(), eq(BOOKING));
        }
        assertNoNoShowWritten();
    }

    /** The first instant of the action's window: startAt for check-in, startAt + 15:00 for the other two. */
    private static Instant windowOpens(AttendanceAction action) {
        return action == AttendanceAction.CHECK_IN ? START : LATE_OPENS;
    }

    private void assertNoNoShowWritten() {
        verify(store, never()).record(any(), any(), any(), any(), any());
        verify(bookings, never()).markNoShow(any(), any(), any(), any(), any());
        verify(bookings, never()).recordVenueEvent(any(), any(), any(), any(), any());
        verify(ledger, never()).release(any(), any(), any(), any());
        verify(trust, never()).recordNoShow(any(), any(), any(), any());
        verify(rewards, never()).reconcile(any(), any(), any());
    }

    private void confirmed(DelayedStart delayedStart) {
        when(bookings.find(any(), eq(BOOKING))).thenReturn(Optional.of(new Booking(BOOKING, VENUE, ROOM, CUSTOMER,
                BookingSource.ONLINE, BookingStatus.CONFIRMED, START, END)));
        when(bookings.resolveDelayedStart(any(), eq(BOOKING))).thenReturn(delayedStart);
    }

    private RecordedAttendance recorded(Instant now, AttendanceAction action) {
        CommandAnswer<RecordedAttendance> answer = commands.record(at(now), action, BOOKING, VENUE, ACCOUNT);
        assertThat(answer).isInstanceOf(CommandAnswer.Ok.class);
        return ((CommandAnswer.Ok<RecordedAttendance>) answer).value();
    }

    /** Whether the rule lets {@code action} through at {@code now}; a refusal must be the action's own code. */
    private boolean permitted(Instant now, AttendanceAction action) {
        try {
            commands.record(at(now), action, BOOKING, VENUE, ACCOUNT);
            return true;
        } catch (ApiException refused) {
            assertThat(refused.code()).as("%s at %s", action, now).isEqualTo(action.refusal());
            return false;
        }
    }

    private void assertRefused(Instant now, AttendanceAction action, ApiErrorCode code) {
        assertThatThrownBy(() -> recorded(now, action))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
