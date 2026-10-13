package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.booking.internal.LateConfirmDb.attempt;
import static ps.e7gz.booking.internal.LateConfirmDb.attempts;
import static ps.e7gz.booking.internal.LateConfirmDb.history;
import static ps.e7gz.booking.internal.LateConfirmDb.reservations;
import static ps.e7gz.booking.internal.LateConfirmDb.t2;
import static ps.e7gz.booking.internal.LateConfirmDb.t3;
import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.bookingStart;
import static ps.e7gz.booking.internal.VenueAnswerDb.count;
import static ps.e7gz.booking.internal.VenueAnswerDb.events;
import static ps.e7gz.booking.internal.VenueAnswerDb.job;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ps.e7gz.booking.internal.BookingStates.Plant;
import ps.e7gz.booking.internal.LateConfirmDb.Attempt;
import ps.e7gz.booking.internal.LateConfirmDb.Event;
import ps.e7gz.booking.internal.LateConfirmDb.Reservation;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmAnswer;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmations;
import ps.e7gz.booking.lateconfirm.internal.SentLateConfirmation;
import ps.e7gz.common.codes.BookingEventType;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.messaging.api.CommandAnswer;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;
import ps.e7gz.trust.BanFixtures;

/**
 * E7-58 - Late Confirm initiate, accept and decline (O3) against the migrated database, every transaction committed
 * through the real {@code CommandRunner}: no planted AWAITING booking and no rolled-back transaction - each AWAITING
 * booking here was made by the real Initiate, so the deferred Late Confirm integrity checks
 * ({@code tr_late_confirmation_integrity}, {@code tr_lc_integrity_from_booking}, {@code tr_lc_integrity_from_reservation})
 * ran at its commit. The PO decisions D1-D4 of 12 Oct 2026.
 */
class LateConfirmIntegrationTest extends IntegrationTest {

    @Autowired
    CommandRunner runner;

    @Autowired
    OnlineBookingCreation creation;

    @Autowired
    VenueAnswerStore store;

    @Autowired
    VenueAnswers answers;

    @Autowired
    LateConfirmations lateConfirmations;

    @Autowired
    ManualBookings manual;

    @Autowired
    BookingHistoryStore histories;

    private LateConfirmDb db;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        db = new LateConfirmDb(runner, creation, store, answers);
        correlation = CorrelationId.open(UUID.randomUUID());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    // ---------------------------------------------------------------- A: a real, committed Initiate over an overlap

    @Test
    void initiateOverAnOverlappingCommitmentCommitsTheWholeTransition() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource second = world.anotherRoom(room -> room);
        Instant start = world.start();
        UUID late = db.expired(world, start, 120);
        CreatedBooking other = creation.create(world.request(world.customer.id(), second,
                start.plus(Duration.ofMinutes(30)), 60));
        LateConfirmDb.Amounts before = LateConfirmDb.amounts(late);

        SentLateConfirmation sent = ok(lateConfirmations.initiate(world.account.id(), late));

        // D1, FR-LCF-15: the venue may send it although the customer has an overlapping booking; never checked here
        assertThat(sent.status()).isEqualTo(BookingStatus.AWAITING_CUSTOMER_CONFIRMATION);
        VenueAnswerDb.BookingRow row = booking(late);
        assertThat(row.status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(row.counted()).as("AWAITING keeps its commitment_period (D1)").isTrue();
        assertThat(booking(other.bookingId()).status()).isEqualTo("PENDING");
        assertThat(LateConfirmDb.amounts(late)).as("same Booking ID and every original value (FR-LCF-08)")
                .isEqualTo(before);

        Attempt attempt = attempt(late).orElseThrow();
        Instant initiated = attempt.initiatedAt();
        assertThat(attempt.attemptNo()).isEqualTo(1);
        assertThat(attempt.expiresAt()).isEqualTo(initiated.plus(Duration.ofMinutes(5)))
                .isEqualTo(sent.lateConfirmExpiresAt());
        assertThat(attempt.response()).isNull();
        assertThat(attempt.closedAt()).isNull();

        Reservation hold = reservations(late).stream().filter(r -> r.type().equals("LATE_CONFIRM_HOLD"))
                .findFirst().orElseThrow();
        assertThat(hold.id()).isEqualTo(attempt.holdId());
        assertThat(hold.active()).isTrue();
        assertThat(hold.plannedStart()).isEqualTo(start);
        assertThat(hold.plannedEnd()).isEqualTo(start.plus(Duration.ofMinutes(120)));
        assertThat(hold.actualStart()).isEqualTo(initiated);

        Event last = history(late).getLast();
        assertThat(last.entryKind()).isEqualTo("STATUS_TRANSITION");
        assertThat(last.fromStatus()).isEqualTo("VENUE_RESPONSE_EXPIRED");
        assertThat(last.toStatus()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(last.actorType()).isEqualTo("VENUE");
        assertThat(last.actorId()).isEqualTo(world.account.id());
        assertThat(last.occurredAt()).isEqualTo(initiated);

        assertThat(job(t2(late)).orElseThrow()).satisfies(j -> {
            assertThat(j.type()).isEqualTo("LATE_CONFIRM_EXPIRY");
            assertThat(j.status()).isEqualTo("PENDING");
            assertThat(j.dueAt()).isEqualTo(attempt.expiresAt());
        });
        assertThat(job(t3(late)).orElseThrow()).satisfies(j -> {
            assertThat(j.type()).isEqualTo("LATE_CONFIRM_WARNING");
            assertThat(j.dueAt()).isEqualTo(attempt.expiresAt().minusSeconds(60));
        });
        assertThat(events("LateConfirmInitiated", late)).singleElement()
                .satisfies(payload -> assertThat(payload).contains("\"bookingId\": \"" + late + "\"")
                        .contains("\"lateConfirmExpiresAt\": \"" + attempt.expiresAt() + "\"")
                        .contains("\"venueName\": \"" + world.venue.name() + "\"")
                        .doesNotContain("phone").doesNotContain("LIMIT"));
        // the ledger's own event for the hold (E7-46 convention: aggregate RESERVATION, the reservation id)
        assertThat(events("ReservationHeld", hold.id())).singleElement();
        assertThat(events("BookingConfirmed", late)).isEmpty();
    }

    // ---------------------------------------------------------------- B: FR-BOK-04 against a real AWAITING booking

    @Test
    void aNewOnlineBookingOverlappingARealAwaitingOneIsRefusedByTheCreationPredicate() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource second = world.anotherRoom(room -> room);
        Instant start = world.start();
        UUID late = db.expired(world, start, 120);
        ok(lateConfirmations.initiate(world.account.id(), late));

        assertThatThrownBy(() -> creation.create(world.request(world.customer.id(), second,
                start.plus(Duration.ofMinutes(60)), 60)))
                .isInstanceOfSatisfying(CreationRefused.class, refused ->
                        assertThat(refused.condition()).isEqualTo(OnlineCreationCondition.NO_OVERLAPPING_COMMITMENT));
        assertThat(count("booking.booking WHERE resource_id = :id", second.id())).isZero();
    }

    // ---------------------------------------------------------------- C: a conflicting Accept commits its event

    @Test
    void aConflictingAcceptCommitsItsEventAnswers409AndLeavesTheBookingAwaitingWithItsHold() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource second = world.anotherRoom(room -> room);
        Instant start = world.start();
        UUID late = db.expired(world, start, 120);
        creation.create(world.request(world.customer.id(), second, start.plus(Duration.ofMinutes(30)), 60));
        ok(lateConfirmations.initiate(world.account.id(), late));
        Attempt before = attempt(late).orElseThrow();
        int historyBefore = history(late).size();

        CommandAnswer<LateConfirmAnswer> answer = lateConfirmations.accept(world.customer.id(), late);

        assertRefusedWith(answer, ApiErrorCode.LATE_CONFIRM_CUSTOMER_CONFLICT);
        List<Event> rows = history(late);
        assertThat(rows).hasSize(historyBefore + 1);
        assertThat(rows.getLast()).satisfies(event -> {
            assertThat(event.entryKind()).isEqualTo("BOOKING_EVENT");
            assertThat(event.eventType()).isEqualTo("LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT");
            assertThat(event.actorType()).isEqualTo("CUSTOMER");
            assertThat(event.actorId()).isEqualTo(world.customer.id());
        });
        assertThat(booking(late).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(attempt(late).orElseThrow()).as("open, same deadline").isEqualTo(before);
        assertThat(reservations(late)).filteredOn(Reservation::active).singleElement()
                .satisfies(r -> assertThat(r.id()).isEqualTo(before.holdId()));
        assertThat(job(t2(late)).orElseThrow().status()).isEqualTo("PENDING");
        assertThat(events("LateConfirmAccepted", late)).isEmpty();
        // NFR-19: the venue's view of the history never shows it
        assertThat(venueHistory(late)).noneMatch(entry ->
                entry.eventType() == BookingEventType.LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT);
    }

    // ---------------------------------------------------------------- D: a successful Accept

    @Test
    void anAcceptConfirmsTheSameBookingMovesTheHoldToItsBookingReservationAndClosesTheAttempt() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant start = world.start();
        UUID late = db.expired(world, start, 90);
        ok(lateConfirmations.initiate(world.account.id(), late));
        Attempt open = attempt(late).orElseThrow();
        LateConfirmDb.Amounts before = LateConfirmDb.amounts(late);

        LateConfirmAnswer answer = ok(lateConfirmations.accept(world.customer.id(), late));

        assertThat(answer).isEqualTo(new LateConfirmAnswer(late, BookingStatus.CONFIRMED, null));
        assertThat(booking(late).status()).isEqualTo("CONFIRMED");
        assertThat(booking(late).counted()).isTrue();
        assertThat(LateConfirmDb.amounts(late)).as("same Booking ID, price and fee (FR-LCF-08, FR-PTS-13)")
                .isEqualTo(before);
        Attempt closed = attempt(late).orElseThrow();
        assertThat(closed.response()).isEqualTo("ACCEPTED");
        assertThat(closed.respondedAt()).isEqualTo(closed.closedAt()).isBefore(open.expiresAt());
        Instant acceptedAt = closed.respondedAt();

        List<Reservation> rows = reservations(late);
        assertThat(rows).filteredOn(r -> r.type().equals("LATE_CONFIRM_HOLD")).singleElement().satisfies(hold -> {
            assertThat(hold.active()).isFalse();
            assertThat(hold.releasedAt()).isEqualTo(acceptedAt);
        });
        assertThat(rows).filteredOn(Reservation::active).singleElement().satisfies(reservation -> {
            assertThat(reservation.type()).isEqualTo("BOOKING");
            assertThat(reservation.plannedStart()).isEqualTo(start);
            assertThat(reservation.plannedEnd()).isEqualTo(start.plus(Duration.ofMinutes(90)));
            assertThat(reservation.actualStart()).isEqualTo(acceptedAt);
        });

        Event last = history(late).getLast();
        assertThat(last.fromStatus()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(last.toStatus()).isEqualTo("CONFIRMED");
        assertThat(last.actorType()).isEqualTo("CUSTOMER");
        assertThat(last.occurredAt()).isEqualTo(acceptedAt);
        assertThat(job(t2(late)).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(job(t3(late)).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(job(bookingStart(late)).orElseThrow()).satisfies(t4 -> {
            assertThat(t4.status()).isEqualTo("PENDING");
            assertThat(t4.dueAt()).isEqualTo(start);
        });
        assertThat(events("LateConfirmAccepted", late)).singleElement();
        assertThat(events("BookingConfirmed", late)).as("D2: O3 writes only its own events").isEmpty();
        Reservation bookingReservation = rows.stream().filter(Reservation::active).findFirst().orElseThrow();
        Reservation releasedHold = rows.stream().filter(r -> r.type().equals("LATE_CONFIRM_HOLD")).findFirst()
                .orElseThrow();
        assertThat(events("ReservationReleased", releasedHold.id())).singleElement();
        assertThat(events("ReservationHeld", bookingReservation.id())).singleElement();
    }

    // ---------------------------------------------------------------- the window (FR-LCF-09, FR-LCF-12)

    @Test
    void aWindowOfSixtySecondsOrLessEndsAtTheStartAndHasNoWarningJob() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        Instant near = OnlineBookingWorld.dbNow().plusSeconds(45);
        BookingStates.plant(late, Plant.of("VENUE_RESPONSE_EXPIRED").startAt(near));

        ok(lateConfirmations.initiate(world.account.id(), late));

        Attempt attempt = attempt(late).orElseThrow();
        assertThat(attempt.expiresAt()).as("MIN(now + 5 min, startAt) is the start").isEqualTo(near);
        assertThat(Duration.between(attempt.initiatedAt(), attempt.expiresAt())).isLessThanOrEqualTo(
                Duration.ofSeconds(60));
        assertThat(job(t2(late)).orElseThrow().dueAt()).isEqualTo(near);
        assertThat(job(t3(late))).as("FR-LCF-12: never created, nothing to suppress").isEmpty();
    }

    @Test
    void aWindowEndingAtTheStartButLongerThanSixtySecondsHasOneWarningAMinuteBefore() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        Instant near = OnlineBookingWorld.dbNow().plusSeconds(180);
        BookingStates.plant(late, Plant.of("VENUE_RESPONSE_EXPIRED").startAt(near));

        ok(lateConfirmations.initiate(world.account.id(), late));

        assertThat(attempt(late).orElseThrow().expiresAt()).isEqualTo(near);
        assertThat(job(t3(late)).orElseThrow().dueAt()).isEqualTo(near.minusSeconds(60));
        assertThat(count("technical.scheduled_job WHERE subject_id = :id AND type = 'LATE_CONFIRM_WARNING'", late))
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- D4: the customer, and what the venue learns

    @Test
    void aCustomerAtFourCommitmentsIsBlockedWithTheEventCommittedNoHoldAndTheNeutralCode() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant start = world.start();
        UUID late = db.expired(world, start, 60);
        for (int i = 1; i <= 4; i++) {
            creation.create(world.request(start.plus(Duration.ofHours(i)), 60));
        }

        CommandAnswer<SentLateConfirmation> answer = lateConfirmations.initiate(world.account.id(), late);

        assertRefusedWith(answer, ApiErrorCode.CUSTOMER_NOT_ELIGIBLE_FOR_LATE_CONFIRM);
        assertThat(((CommandAnswer.Refused<SentLateConfirmation>) answer).refusal())
                .satisfies(refusal -> {
                    assertThat(refusal.details()).as("no details").isNullOrEmpty();
                    assertThat(refusal.field()).as("no field").isNull();
                });
        assertThat(booking(late).status()).isEqualTo("VENUE_RESPONSE_EXPIRED");
        assertThat(attempts(late)).isZero();
        assertThat(reservations(late)).noneMatch(Reservation::active)
                .noneMatch(r -> r.type().equals("LATE_CONFIRM_HOLD"));
        assertThat(history(late).getLast()).satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("LATE_CONFIRM_FAILED_BOOKING_LIMIT");
            assertThat(event.actorType()).isEqualTo("VENUE");
        });
        assertThat(job(t2(late))).isEmpty();
        assertThat(events("LateConfirmInitiated", late)).isEmpty();
        assertThat(venueHistory(late)).noneMatch(entry ->
                entry.eventType() == BookingEventType.LATE_CONFIRM_FAILED_BOOKING_LIMIT);
    }

    @Test
    void aBannedCustomerIsTheNeutralCodeWithNothingWritten() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        BanFixtures.activeBan(world.customer.id());
        int historyBefore = history(late).size();

        assertThrownWith(() -> lateConfirmations.initiate(world.account.id(), late),
                ApiErrorCode.CUSTOMER_NOT_ELIGIBLE_FOR_LATE_CONFIRM);
        assertThat(history(late)).as("D4: no booking event for a ban").hasSize(historyBefore);
        assertThat(booking(late).status()).isEqualTo("VENUE_RESPONSE_EXPIRED");
        assertThat(attempts(late)).isZero();
        assertThat(reservations(late)).noneMatch(Reservation::active);
    }

    @Test
    void aDeactivatedCustomerIsTheNeutralCodeWithNothingWritten() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql(
                        "UPDATE iam.user_account SET phone_verified = false, phone_verified_at = NULL WHERE id = :id")
                .param("id", world.customer.id()).update());
        int historyBefore = history(late).size();

        assertThrownWith(() -> lateConfirmations.initiate(world.account.id(), late),
                ApiErrorCode.CUSTOMER_NOT_ELIGIBLE_FOR_LATE_CONFIRM);
        assertThat(history(late)).hasSize(historyBefore);
        assertThat(attempts(late)).isZero();
    }

    // ---------------------------------------------------------------- the venue and the room (FR-LCF-02, FR-LCF-03)

    @Test
    void anotherVenuesBookingIsNotFound() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        OnlineBookingWorld otherVenue = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);

        assertThrownWith(() -> lateConfirmations.initiate(otherVenue.account.id(), late), ApiErrorCode.NOT_FOUND);
        assertThat(attempts(late)).isZero();
    }

    @Test
    void onlyAnExpiredBookingBeforeItsStartCanBeSent() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking pending = creation.create(world.request(world.start(), 60));
        assertThrownWith(() -> lateConfirmations.initiate(world.account.id(), pending.bookingId()),
                ApiErrorCode.BOOKING_STATE_CHANGED);

        UUID late = db.expired(world, world.start().plus(Duration.ofHours(2)), 60);
        ok(lateConfirmations.initiate(world.account.id(), late));
        assertThrownWith(() -> lateConfirmations.initiate(world.account.id(), late),
                ApiErrorCode.BOOKING_STATE_CHANGED);
        assertThat(attempts(late)).isEqualTo(1);

        UUID started = db.expired(world, world.start().plus(Duration.ofHours(4)), 60);
        BookingStates.plant(started, Plant.of("VENUE_RESPONSE_EXPIRED")
                .startAt(OnlineBookingWorld.dbNow().minusSeconds(1)));
        assertThrownWith(() -> lateConfirmations.initiate(world.account.id(), started),
                ApiErrorCode.DEADLINE_PASSED);
    }

    /**
     * A Manual Booking is never revived (O3 is for Online Bookings only, FR-LCF-01): the venue's own booking answers
     * BOOKING_STATE_CHANGED with nothing written. The database cannot hold a MANUAL booking in
     * VENUE_RESPONSE_EXPIRED, so the source and the status check refuse it together.
     */
    @Test
    void aManualBookingCannotBeSent() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID manualBooking = manual.create(world.account.id(), ManualBookingRequest.of(world.room.id(),
                PlayMode.SINGLE, world.start(), 60, null, null)).bookingId();
        List<Event> before = history(manualBooking);

        assertThrownWith(() -> lateConfirmations.initiate(world.account.id(), manualBooking),
                ApiErrorCode.BOOKING_STATE_CHANGED);

        assertThat(booking(manualBooking).status()).isEqualTo("CONFIRMED");
        assertThat(attempts(manualBooking)).isZero();
        assertThat(history(manualBooking)).isEqualTo(before);
        assertThat(job(t2(manualBooking))).isEmpty();
        assertThat(events(IntegrationEvents.LATE_CONFIRM_INITIATED, manualBooking)).isEmpty();
    }

    @Test
    void anOnboardingVenueIsForbidden() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        VenueAnswerDb.marketplaceStatus(world.venue.id(), MarketplaceStatus.ONBOARDING.name());

        assertThrownWith(() -> lateConfirmations.initiate(world.account.id(), late), ApiErrorCode.FORBIDDEN);
    }

    @Test
    void aSuspendedVenueMaySendIt() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        VenueAnswerDb.marketplaceStatus(world.venue.id(), MarketplaceStatus.SUSPENDED.name());

        ok(lateConfirmations.initiate(world.account.id(), late));
    }

    @Test
    void theRoomTakenByAnotherBookingCommitsTheResourceConflictEventAndAnswers409() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant start = world.start();
        UUID late = db.expired(world, start, 60);
        creation.create(world.request(world.anotherCustomer(), world.room, start, 60));
        int historyBefore = history(late).size();

        CommandAnswer<SentLateConfirmation> answer = lateConfirmations.initiate(world.account.id(), late);

        // Q2, FR-LCF-17: committed with its event, the booking and the room unchanged
        assertRefusedWith(answer, ApiErrorCode.LATE_CONFIRM_RESOURCE_CONFLICT);
        assertThat(history(late)).hasSize(historyBefore + 1);
        assertThat(history(late).getLast()).satisfies(event -> {
            assertThat(event.entryKind()).isEqualTo("BOOKING_EVENT");
            assertThat(event.eventType()).isEqualTo("LATE_CONFIRM_FAILED_RESOURCE_CONFLICT");
            assertThat(event.actorType()).isEqualTo("VENUE");
            assertThat(event.actorId()).isEqualTo(world.account.id());
        });
        assertThat(booking(late).status()).isEqualTo("VENUE_RESPONSE_EXPIRED");
        assertThat(attempts(late)).isZero();
        assertThat(reservations(late)).noneMatch(Reservation::active);
        assertThat(events("LateConfirmInitiated", late)).isEmpty();
    }

    @Test
    void aVenueEmergencyIsAVenueSellingBlock() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        VenueAnswerDb.venueEmergency(world.venue.id());

        assertThrownWith(() -> lateConfirmations.initiate(world.account.id(), late),
                ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE);
    }

    // ---------------------------------------------------------------- Accept: ownership, pause, deadline, state

    @Test
    void anotherCustomerCannotAnswer() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        ok(lateConfirmations.initiate(world.account.id(), late));
        UUID stranger = world.anotherCustomer();

        assertThrownWith(() -> lateConfirmations.accept(stranger, late), ApiErrorCode.NOT_FOUND);
        assertThrownWith(() -> lateConfirmations.decline(stranger, late), ApiErrorCode.NOT_FOUND);
        assertThat(booking(late).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
    }

    @Test
    void acceptWhilePausedIsRefusedWithoutChangeVenueOrResourceSellingBlock() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        ok(lateConfirmations.initiate(world.account.id(), late));
        LateConfirmDb.lateConfirmPaused(late);
        Attempt before = attempt(late).orElseThrow();
        int historyBefore = history(late).size();

        assertThrownWith(() -> lateConfirmations.accept(world.customer.id(), late),
                ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE);
        VenueAnswerDb.resourceEmergency(world.venue.id(), world.room.id());
        assertThrownWith(() -> lateConfirmations.accept(world.customer.id(), late),
                ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE);

        assertThat(booking(late).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(attempt(late).orElseThrow()).isEqualTo(before);
        assertThat(history(late)).hasSize(historyBefore);
    }

    @Test
    void acceptAndDeclineAtOrAfterTheStoredDeadlineAreDeadlinePassed() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        ok(lateConfirmations.initiate(world.account.id(), late));
        LateConfirmDb.deadlinePassed(late);

        assertThrownWith(() -> lateConfirmations.accept(world.customer.id(), late), ApiErrorCode.DEADLINE_PASSED);
        assertThrownWith(() -> lateConfirmations.decline(world.customer.id(), late), ApiErrorCode.DEADLINE_PASSED);
        assertThat(booking(late).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(attempt(late).orElseThrow().closedAt()).isNull();
    }

    @Test
    void aBannedCustomerCannotAccept() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        ok(lateConfirmations.initiate(world.account.id(), late));
        BanFixtures.activeBan(world.customer.id());

        assertThrownWith(() -> lateConfirmations.accept(world.customer.id(), late),
                ApiErrorCode.ACCOUNT_BANNED_FOR_THIS_ACTION);
        assertThat(booking(late).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(attempt(late).orElseThrow().closedAt()).isNull();
        // A Decline by this banned customer is not tested: no consistent state has a banned customer with an open
        // AWAITING booking. Ban activation cancels it in the same command (USER_BANNED, the ban cascade), and while a
        // cascade is open trust.guard_booking_open_ban_cascade refuses any other transition of the customer's bookings
        // (Phase 2B.1 report). This fixture's Ban has no executed cascade, so only the refused Accept is meaningful.
    }

    @Test
    void anAnsweredLateConfirmationCannotBeAnsweredAgain() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        ok(lateConfirmations.initiate(world.account.id(), late));
        ok(lateConfirmations.accept(world.customer.id(), late));

        assertThrownWith(() -> lateConfirmations.accept(world.customer.id(), late),
                ApiErrorCode.BOOKING_STATE_CHANGED);
        assertThrownWith(() -> lateConfirmations.decline(world.customer.id(), late),
                ApiErrorCode.BOOKING_STATE_CHANGED);
    }

    // ---------------------------------------------------------------- Decline

    @Test
    void aDeclineCancelsWithoutPenaltyReleasesTheHoldAndRecordsTheFeeDecision() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        ok(lateConfirmations.initiate(world.account.id(), late));

        LateConfirmAnswer answer = lateConfirmations.decline(world.customer.id(), late);

        assertThat(answer).isEqualTo(new LateConfirmAnswer(late, BookingStatus.CANCELLED,
                BookingTerminalReason.CUSTOMER_DECLINED_LATE_CONFIRM));
        VenueAnswerDb.BookingRow row = booking(late);
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.terminalReason()).isEqualTo("CUSTOMER_DECLINED_LATE_CONFIRM");
        assertThat(row.feeTreatment()).isEqualTo("FULL_REFUND");
        assertThat(row.counted()).isFalse();
        Attempt closed = attempt(late).orElseThrow();
        assertThat(closed.response()).isEqualTo("DECLINED");
        assertThat(row.terminalAt()).isEqualTo(closed.respondedAt()).isEqualTo(closed.closedAt());
        assertThat(reservations(late)).noneMatch(Reservation::active);
        assertThat(history(late).getLast()).satisfies(event -> {
            assertThat(event.toStatus()).isEqualTo("CANCELLED");
            assertThat(event.terminalReason()).isEqualTo("CUSTOMER_DECLINED_LATE_CONFIRM");
            assertThat(event.actorType()).isEqualTo("CUSTOMER");
        });
        assertThat(count("loyalty.booking_fee_refund WHERE booking_id = :id", late)).isEqualTo(1);
        assertThat(count("trust.trust_event WHERE booking_id = :id", late)).as("FR-LCF-13: no penalty").isZero();
        assertThat(job(t2(late)).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(job(t3(late)).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(events("LateConfirmDeclined", late)).singleElement()
                .satisfies(payload -> assertThat(payload).contains("CUSTOMER_DECLINED_LATE_CONFIRM"));
        assertThat(events("BookingCancelled", late)).as("D2: O3 writes only its own events").isEmpty();
        assertThat(events("ReservationReleased", attempt(late).orElseThrow().holdId())).singleElement();
    }

    @Test
    void aDeclineWhilePausedBeforeTheStoredDeadlineSucceedsAndEndsThePause() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        ok(lateConfirmations.initiate(world.account.id(), late));
        LateConfirmDb.lateConfirmPaused(late);
        VenueAnswerDb.venueEmergency(world.venue.id());

        LateConfirmAnswer answer = lateConfirmations.decline(world.customer.id(), late);

        assertThat(answer.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(LateConfirmDb.openLateConfirmPauses(late)).isZero();
        assertThat(OWNER.sql("SELECT late_confirm_pause_started_at IS NULL FROM booking.booking WHERE id = :id")
                .param("id", late).query(Boolean.class).single()).isTrue();
        assertThat(job(t2(late)).orElseThrow().status()).isEqualTo("SUPERSEDED");
    }

    // ---------------------------------------------------------------- concurrency, under the commitment guard

    @Test
    void initiateRacingANewBookingNeverMakesAFifthCommitment() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource second = world.anotherRoom(room -> room);
        Instant start = world.start();
        UUID late = db.expired(world, start, 60);
        for (int i = 1; i <= 3; i++) {
            creation.create(world.request(start.plus(Duration.ofHours(i)), 60));
        }

        List<Object> outcomes = race(List.of(
                () -> lateConfirmations.initiate(world.account.id(), late),
                () -> creation.create(world.request(world.customer.id(), second, start.plus(Duration.ofHours(5)),
                        60))));

        long commitments = count("booking.booking WHERE customer_id = :id AND commitment_period IS NOT NULL",
                world.customer.id());
        assertThat(commitments).as("FR-LCF-06: never a temporary fifth commitment").isEqualTo(4);
        Object initiated = outcomes.get(0);
        Object created = outcomes.get(1);
        if (initiated instanceof CommandAnswer.Ok<?>) {
            assertThat(created).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.code()).isEqualTo(ApiErrorCode.COMMITMENT_LIMIT_REACHED));
        } else {
            assertThat(created).isInstanceOf(CreatedBooking.class);
            assertThat(initiated).isInstanceOfSatisfying(CommandAnswer.Refused.class, refused ->
                    assertThat(refused.refusal().code()).isEqualTo(ApiErrorCode.CUSTOMER_NOT_ELIGIBLE_FOR_LATE_CONFIRM));
            assertThat(booking(late).status()).isEqualTo("VENUE_RESPONSE_EXPIRED");
        }
    }

    @Test
    void initiateRacingAnOverlappingNewBookingEndsInALegalStateEitherWay() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource second = world.anotherRoom(room -> room);
        Instant start = world.start();
        UUID late = db.expired(world, start, 120);

        List<Object> outcomes = race(List.of(
                () -> lateConfirmations.initiate(world.account.id(), late),
                () -> creation.create(world.request(world.customer.id(), second, start.plus(Duration.ofMinutes(30)),
                        60))));

        // D1: initiation never checks the schedule, so it always succeeds; the new booking is refused exactly when
        // the commitment guard ordered it after the initiation (FR-BOK-04), and accepted when it came first.
        assertThat(outcomes.get(0)).isInstanceOf(CommandAnswer.Ok.class);
        assertThat(booking(late).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        Object created = outcomes.get(1);
        if (created instanceof ApiException refused) {
            assertThat(refused.code()).isEqualTo(ApiErrorCode.OVERLAPPING_COMMITMENT);
            assertThat(count("booking.booking WHERE resource_id = :id", second.id())).isZero();
        } else {
            assertThat(created).isInstanceOf(CreatedBooking.class);
            // the customer's Accept then sees the conflict
            assertRefusedWith(lateConfirmations.accept(world.customer.id(), late),
                    ApiErrorCode.LATE_CONFIRM_CUSTOMER_CONFLICT);
        }
    }

    @Test
    void acceptRacingDeclineHasExactlyOneWinner() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        ok(lateConfirmations.initiate(world.account.id(), late));

        List<Object> outcomes = race(List.of(
                () -> lateConfirmations.accept(world.customer.id(), late),
                () -> lateConfirmations.decline(world.customer.id(), late)));

        long winners = outcomes.stream().filter(o -> o instanceof CommandAnswer.Ok<?> || o instanceof LateConfirmAnswer)
                .count();
        assertThat(winners).isEqualTo(1);
        assertThat(outcomes).filteredOn(ApiException.class::isInstance).singleElement()
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED));
        assertThat(attempt(late).orElseThrow().closedAt()).isNotNull();
    }

    @Test
    void twoInitiationsAtOnceMakeOneAttempt() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);

        List<Object> outcomes = race(List.of(
                () -> lateConfirmations.initiate(world.account.id(), late),
                () -> lateConfirmations.initiate(world.account.id(), late)));

        assertThat(outcomes).filteredOn(CommandAnswer.Ok.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(ApiException.class::isInstance).singleElement()
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED));
        assertThat(attempts(late)).isEqualTo(1);
        assertThat(reservations(late)).filteredOn(Reservation::active).hasSize(1);
    }

    // ---------------------------------------------------------------- helpers

    private List<BookingHistoryEntry> venueHistory(UUID bookingId) {
        return runner.run(new OnlineBookingCreationIntegrationTest.ReadCommand<>(
                context -> histories.forVenue(context, bookingId)));
    }

    static <R> R ok(CommandAnswer<R> answer) {
        assertThat(answer).isInstanceOf(CommandAnswer.Ok.class);
        return ((CommandAnswer.Ok<R>) answer).value();
    }

    static void assertRefusedWith(CommandAnswer<?> answer, ApiErrorCode code) {
        assertThat(answer).isInstanceOfSatisfying(CommandAnswer.Refused.class,
                refused -> assertThat(refused.refusal().code()).isEqualTo(code));
    }

    static void assertThrownWith(ThrowingCallable call, ApiErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    /** Starts every task at once; each outcome is the task's result or the ApiException it threw. */
    static List<Object> race(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    try (CorrelationId.Scope ignored = CorrelationId.open(UUID.randomUUID())) {
                        return task.call();
                    } catch (ApiException refused) {
                        return refused;
                    }
                }));
            }
            go.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
