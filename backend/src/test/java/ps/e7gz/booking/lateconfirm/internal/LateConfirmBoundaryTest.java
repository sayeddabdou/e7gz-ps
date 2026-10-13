package ps.e7gz.booking.lateconfirm.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import ps.e7gz.booking.api.LateConfirmBookings;
import ps.e7gz.booking.api.LateConfirmBookings.Booking;
import ps.e7gz.booking.internal.LateConfirmTestWorld;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.geography.api.AreaLookup;
import ps.e7gz.identity.api.CustomerActivation;
import ps.e7gz.inventory.availability.api.AvailabilityQuery;
import ps.e7gz.inventory.availability.api.PredicateResult;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.loyalty.api.BookingFeeRefunds;
import ps.e7gz.messaging.api.CommandAnswer;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.operations.api.ResponseTimerPauses;
import ps.e7gz.operations.api.UnresolvedEmergencies;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.NewJob;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.Boundary;
import ps.e7gz.support.boundary.BoundaryAssertions;
import ps.e7gz.support.boundary.CommandInstants;
import ps.e7gz.support.boundary.Deadlines;
import ps.e7gz.support.boundary.DueJobs;
import ps.e7gz.support.boundary.TestCommands;
import ps.e7gz.trust.api.ActiveBans;
import ps.e7gz.venue.api.OperatingSessions;
import ps.e7gz.venue.api.SessionSnapshots;
import ps.e7gz.venue.api.VenueContacts;
import ps.e7gz.venue.api.VenueEligibility;

/**
 * T2 Late Confirm expiry, due {@code MIN(initiation + 5 min, booking.startAt)}, and T3 its warning, created only when
 * the window is longer than 60 s (arch 08 sections 1, 2.6 and 9; FR-LCF-09, FR-LCF-12; roadmap Phase 3 exit). The
 * E7-45 boundary tests of E7-58, moved here from {@code support.boundary.pending} and enabled (E7-58 Phase 2C).
 *
 * <p>The first two need a command instant the database clock cannot give - exactly 19:59:00 and 19:50:00 for a 20:00
 * start - so they run the real Initiate body ({@link LateConfirmCommands#initiate}) at that instant with its
 * collaborators mocked ({@link CommandInstants}, as E7-47's {@code RejectAfterExpiryBoundaryTest}); the database
 * guards, which re-check the same rule, are exercised by the committed Initiates of {@code LateConfirmIntegrationTest}.
 * The other three run on the database. An open {@code expires_at} may only move later
 * ({@code guard_late_confirmation_mutation}), so each starts from an attempt whose deadline was set a millisecond after
 * its initiation and is then moved later, through the real guard, to the side of "now" under test.
 */
class LateConfirmBoundaryTest extends IntegrationTest {

    private static final Instant EIGHT_PM = Instant.parse("2026-10-13T17:00:00Z");                  // 20:00 Cairo
    private static final UUID BOOKING = UUID.fromString("018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f");
    private static final UUID VENUE = UUID.fromString("018f3c2a-7b1e-7c4d-9e0f-000000000001");
    private static final UUID ROOM = UUID.fromString("018f3c2a-7b1e-7c4d-9e0f-000000000002");
    private static final UUID CUSTOMER = UUID.fromString("018f3c2a-7b1e-7c4d-9e0f-000000000003");
    private static final UUID ACCOUNT = UUID.fromString("018f3c2a-7b1e-7c4d-9e0f-000000000004");

    @Autowired
    ApplicationContext context;

    @Autowired
    CommandRunner runner;

    @Autowired
    LateConfirmations lateConfirmations;

    @Autowired
    LateConfirmCommands commands;

    private CorrelationId.Scope correlation;

    @BeforeEach
    void openCorrelation() {
        correlation = CorrelationId.open(UUID.randomUUID());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    @Test
    void initiatedAt759ForAnEightPmStartTheWindowIsExactlySixtySecondsWithNoWarning() {
        // startAt 20:00, initiation 19:59:00 -> expires 20:00:00 (startAt is the earlier), exactly 60 s;
        // no T3 job is written and no warning notification is ever sent.
        Initiated initiated = initiateAt(EIGHT_PM.minusSeconds(60));

        assertThat(initiated.sent().lateConfirmExpiresAt()).isEqualTo(EIGHT_PM);
        assertThat(initiated.jobs()).containsExactly(
                NewJob.of(LateConfirmWindow.EXPIRY_JOB, LateConfirmWindow.BOOKING_SUBJECT, BOOKING, EIGHT_PM));
        verify(initiated.store()).insert(any(), eq(BOOKING), eq(EIGHT_PM.minusSeconds(60)), eq(EIGHT_PM), any());
    }

    @Test
    void initiatedAt750TheWindowIsFiveMinutesWithOneWarningAboutAMinuteBefore() {
        // startAt 20:00, initiation 19:50:00 -> expires 19:55:00; exactly one T3 job, due about 19:54:00.
        Initiated initiated = initiateAt(EIGHT_PM.minusSeconds(600));

        Instant expires = EIGHT_PM.minusSeconds(300);
        assertThat(initiated.sent().lateConfirmExpiresAt()).isEqualTo(expires);
        assertThat(initiated.jobs()).hasSize(2);
        assertThat(initiated.jobs()).filteredOn(job -> job.type().equals(LateConfirmWindow.WARNING_JOB))
                .singleElement().satisfies(warning ->
                        BoundaryAssertions.assertWithinOneSecond(warning.dueAt(), EIGHT_PM.minusSeconds(360)));
        assertThat(initiated.jobs()).filteredOn(job -> job.type().equals(LateConfirmWindow.EXPIRY_JOB))
                .singleElement().satisfies(expiry -> assertThat(expiry.dueAt()).isEqualTo(expires));
    }

    @ParameterizedTest
    @EnumSource(Boundary.class)
    void theCustomerAcceptsOnlyBeforeTheLateConfirmExpiry(Boundary side) {
        LateConfirmTestWorld world = LateConfirmTestWorld.create(context);
        UUID late = awaiting(world);
        UUID attempt = LateConfirmTestWorld.attempt(late).id();
        LateConfirmTestWorld.deadlineJustAfterInitiation(late);

        // Deadlines.moveTo, then the customer accepts in the same command
        boolean permitted;
        try {
            CommandAnswer<LateConfirmAnswer> answer = runner.run(TestCommands.under(scope(late), command -> {
                Deadlines.moveTo(command, side, "booking.late_confirmation.expires_at", attempt);
                return commands.accept(command, late, world.customerId());
            }));
            permitted = answer instanceof CommandAnswer.Ok<?>;
        } catch (ApiException refused) {
            assertThat(refused.code()).isEqualTo(ApiErrorCode.DEADLINE_PASSED);
            permitted = false;
        }

        BoundaryAssertions.assertBoundaryRule(side, permitted);
        assertThat(LateConfirmTestWorld.status(late))
                .isEqualTo(permitted ? "CONFIRMED" : "AWAITING_CUSTOMER_CONFIRMATION");
    }

    @ParameterizedTest
    @EnumSource(Boundary.class)
    void t2ExpiresTheLateConfirmAtItsDeadlineAndNotASecondBefore(Boundary side) {
        LateConfirmTestWorld world = LateConfirmTestWorld.create(context);
        UUID late = awaiting(world);
        UUID attempt = LateConfirmTestWorld.attempt(late).id();
        LateConfirmTestWorld.deadlineJustAfterInitiation(late);
        DueJobs due = DueJobs.of(context, new LateConfirmExpiryHandler(lateConfirmations));
        String t2 = LateConfirmWindow.expiryKey(late);

        Instant deadline = due.fireAt(scope(late), t2, side,
                (command, at) -> Deadlines.move(command, "booking.late_confirmation.expires_at", attempt, at));

        if (side.beforeDeadline()) {
            // one second before: rescheduled to the deadline, nothing changes
            assertThat(LateConfirmTestWorld.status(late)).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
            assertThat(DueJobs.job(t2).status()).isEqualTo("PENDING");
            assertThat(DueJobs.job(t2).dueAt()).isEqualTo(deadline);
        } else {
            // at and after: expired as of the deadline itself
            assertThat(LateConfirmTestWorld.status(late)).isEqualTo("CANCELLED");
            assertThat(LateConfirmTestWorld.terminalReason(late)).isEqualTo("CUSTOMER_RECONFIRM_TIMEOUT");
            assertThat(LateConfirmTestWorld.terminalAt(late)).isEqualTo(deadline);
            assertThat(LateConfirmTestWorld.attempt(late).response()).isEqualTo("TIMED_OUT");
            assertThat(LateConfirmTestWorld.attempt(late).respondedAt()).isEqualTo(deadline);
            assertThat(DueJobs.job(t2).status()).isEqualTo("DONE");
        }
    }

    /** D3: Decline needs {@code now < expires_at} (ck_late_confirmation_response_shape), exactly as Accept. */
    @ParameterizedTest
    @EnumSource(Boundary.class)
    void theCustomerDeclinesOnlyBeforeTheLateConfirmExpiry(Boundary side) {
        LateConfirmTestWorld world = LateConfirmTestWorld.create(context);
        UUID late = awaiting(world);
        UUID attempt = LateConfirmTestWorld.attempt(late).id();
        LateConfirmTestWorld.deadlineJustAfterInitiation(late);

        boolean permitted;
        try {
            LateConfirmAnswer answer = runner.run(TestCommands.under(scope(late), command -> {
                Deadlines.moveTo(command, side, "booking.late_confirmation.expires_at", attempt);
                return commands.decline(command, late, world.customerId());
            }));
            assertThat(answer.status()).isEqualTo(BookingStatus.CANCELLED);
            permitted = true;
        } catch (ApiException refused) {
            assertThat(refused.code()).isEqualTo(ApiErrorCode.DEADLINE_PASSED);
            permitted = false;
        }

        BoundaryAssertions.assertBoundaryRule(side, permitted);
        if (permitted) {
            assertThat(LateConfirmTestWorld.terminalReason(late)).isEqualTo("CUSTOMER_DECLINED_LATE_CONFIRM");
            assertThat(LateConfirmTestWorld.attempt(late).response()).isEqualTo("DECLINED");
        } else {
            assertThat(LateConfirmTestWorld.status(late)).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
            assertThat(LateConfirmTestWorld.attempt(late).response()).isNull();
        }
    }

    /**
     * T3 is due at {@code warningDueAt = expires_at - 60 s} (FR-LCF-12): one second before it is rescheduled to it
     * with nothing sent; at it and one second after (still before the expiry) exactly one warning, and a second fire
     * sends nothing more.
     */
    @ParameterizedTest
    @EnumSource(Boundary.class)
    void t3WarnsOnceFromItsDueInstantAndNotASecondBefore(Boundary side) {
        LateConfirmTestWorld world = LateConfirmTestWorld.create(context);
        UUID late = awaiting(world);
        UUID attempt = LateConfirmTestWorld.attempt(late).id();
        LateConfirmTestWorld.deadlineJustAfterInitiation(late);
        DueJobs due = DueJobs.of(context, new LateConfirmWarningHandler(lateConfirmations));
        String t3 = LateConfirmWindow.warningKey(late);

        Instant warningDueAt = due.fireAt(scope(late), t3, side, (command, at) -> Deadlines.move(command,
                "booking.late_confirmation.expires_at", attempt, at.plus(LateConfirmWindow.WARNING_LEAD)));

        if (side.beforeDeadline()) {
            assertThat(DueJobs.job(t3).status()).isEqualTo("PENDING");
            assertThat(DueJobs.job(t3).dueAt()).isEqualTo(warningDueAt);
            assertThat(LateConfirmTestWorld.warningSentAt(late)).isNull();
            assertThat(LateConfirmTestWorld.events(IntegrationEvents.LATE_CONFIRM_WARNING_DUE, late)).isZero();
        } else {
            assertThat(DueJobs.job(t3).status()).isEqualTo("DONE");
            assertThat(LateConfirmTestWorld.warningSentAt(late)).isNotNull()
                    .isAfterOrEqualTo(warningDueAt)
                    .isBefore(LateConfirmTestWorld.attempt(late).expiresAt());
            assertThat(LateConfirmTestWorld.events(IntegrationEvents.LATE_CONFIRM_WARNING_DUE, late)).isEqualTo(1);
            assertThat(due.fire(t3)).as("a DONE job is never fired again").isFalse();
            ScheduledJob again = new ScheduledJob(UUID.randomUUID(), LateConfirmWindow.WARNING_JOB, "BOOKING", late,
                    Instant.EPOCH, 1, t3, CorrelationId.generate());
            JobOutcome second = runner.run(TestCommands.under(scope(late), command -> commands.warn(command, again)));
            assertThat(second).as("a second worker or a retry").isInstanceOf(JobOutcome.Superseded.class);
            assertThat(LateConfirmTestWorld.events(IntegrationEvents.LATE_CONFIRM_WARNING_DUE, late)).isEqualTo(1);
            assertThat(LateConfirmTestWorld.status(late)).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        }
    }

    @Test
    void aCustomerAtFourCommitmentsIsBlockedWithNoHoldAndTheVenueLearnsNothing() {
        // Initiation for a customer holding 4 commitments is refused; no reservation hold is written; the venue sees
        // only the neutral failure (arch 10 section 5.2).
        LateConfirmTestWorld world = LateConfirmTestWorld.create(context);
        Instant start = world.start();
        UUID late = world.expired(start, 60);
        for (int i = 1; i <= 4; i++) {
            world.pending(start.plus(Duration.ofHours(i)), 60);
        }

        CommandAnswer<SentLateConfirmation> answer = lateConfirmations.initiate(world.venueAccountId(), late);

        assertThat(answer).isInstanceOfSatisfying(CommandAnswer.Refused.class, refused -> {
            ApiException refusal = refused.refusal();
            assertThat(refusal.code()).isEqualTo(ApiErrorCode.CUSTOMER_NOT_ELIGIBLE_FOR_LATE_CONFIRM);
            assertThat(refusal.field()).isNull();
            assertThat(refusal.details()).isNullOrEmpty();
        });
        assertThat(LateConfirmTestWorld.anyHold(late)).isFalse();
        assertThat(LateConfirmTestWorld.attempts(late)).isZero();
        assertThat(LateConfirmTestWorld.status(late)).isEqualTo("VENUE_RESPONSE_EXPIRED");
        assertThat(LateConfirmTestWorld.bookingEvents(late)).containsExactly("LATE_CONFIRM_FAILED_BOOKING_LIMIT");
        assertThat(world.commitments()).isEqualTo(4);
    }

    // ---------------------------------------------------------------- helpers

    private UUID awaiting(LateConfirmTestWorld world) {
        UUID late = world.expired(world.start(), 60);
        assertThat(lateConfirmations.initiate(world.venueAccountId(), late)).isInstanceOf(CommandAnswer.Ok.class);
        return late;
    }

    private CommandScope scope(UUID bookingId) {
        return CommandScope.shared(lateConfirmations.lockSetOf(bookingId));
    }

    private record Initiated(SentLateConfirmation sent, List<NewJob> jobs, LateConfirmStore store) {
    }

    /**
     * The real Initiate body at exactly {@code now}, for an expired 20:00-21:00 booking whose venue, room and customer
     * are all eligible; the collaborators are mocks, so only the rule decides the window and the jobs.
     */
    private static Initiated initiateAt(Instant now) {
        LateConfirmBookings bookings = mock(LateConfirmBookings.class);
        LateConfirmStore store = mock(LateConfirmStore.class);
        VenueEligibility venues = mock(VenueEligibility.class);
        AreaLookup areas = mock(AreaLookup.class);
        OperatingSessions sessions = mock(OperatingSessions.class);
        SessionSnapshots snapshots = mock(SessionSnapshots.class);
        AvailabilityQuery availability = mock(AvailabilityQuery.class);
        ReservationLedger ledger = mock(ReservationLedger.class);
        ScheduledJobs jobs = mock(ScheduledJobs.class);
        VenueContacts contacts = mock(VenueContacts.class);
        CommandContext command = CommandInstants.at(now);
        Instant sessionStart = EIGHT_PM.minus(Duration.ofHours(4));
        Instant sessionEnd = EIGHT_PM.plus(Duration.ofHours(4));
        UUID area = UUID.randomUUID();

        when(bookings.find(command, BOOKING)).thenReturn(Optional.of(new Booking(BOOKING, VENUE, ROOM, CUSTOMER,
                BookingSource.ONLINE, BookingStatus.VENUE_RESPONSE_EXPIRED, EIGHT_PM, EIGHT_PM.plusSeconds(3600),
                false)));
        when(bookings.awaitCustomer(command, BOOKING)).thenReturn(true);
        when(venues.find(command, VENUE)).thenReturn(Optional.of(new VenueEligibility.Eligibility(VENUE, area,
                MarketplaceStatus.ACTIVE, VenueAccountStatus.ENABLED)));
        when(areas.isActive(command, area)).thenReturn(true);
        when(sessions.endingAfter(command, VENUE, now)).thenReturn(List.of(
                new OperatingSessions.SessionPeriod(LocalDate.of(2026, 10, 13), sessionStart, sessionEnd)));
        when(snapshots.containing(command, VENUE, EIGHT_PM)).thenReturn(Optional.of(new SessionSnapshots.Session(
                UUID.randomUUID(), LocalDate.of(2026, 10, 13), sessionStart, sessionEnd,
                LocalDateTime.parse("2026-10-13T16:00:00"), LocalDateTime.parse("2026-10-14T00:00:00"),
                "Africa/Cairo")));
        when(availability.isFree(eq(command), eq(ROOM), any(), eq(now))).thenReturn(PredicateResult.free());
        when(ledger.create(eq(command), any())).thenReturn(UUID.randomUUID());
        when(contacts.find(command, VENUE)).thenReturn(Optional.of(new VenueContacts.Contact(VENUE, "Game Zone",
                "+201001234567")));

        LateConfirmCommands initiate = new LateConfirmCommands(bookings, store, venues, areas, sessions, snapshots,
                availability, mock(UnresolvedEmergencies.class), mock(CustomerActivation.class),
                mock(ActiveBans.class), ledger, jobs, mock(Outbox.class), mock(BookingFeeRefunds.class),
                mock(ResponseTimerPauses.class), contacts);
        CommandAnswer<SentLateConfirmation> answer = initiate.initiate(command, BOOKING, VENUE, ACCOUNT);

        assertThat(answer).isInstanceOf(CommandAnswer.Ok.class);
        ArgumentCaptor<NewJob> scheduled = ArgumentCaptor.forClass(NewJob.class);
        verify(jobs, org.mockito.Mockito.atLeast(1)).schedule(eq(command), scheduled.capture());
        verify(bookings, never()).recordEvent(any(), any(), any(), any(), any(), any());
        return new Initiated(((CommandAnswer.Ok<SentLateConfirmation>) answer).value(), scheduled.getAllValues(),
                store);
    }
}
