package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.booking.internal.LateConfirmDb.attempt;
import static ps.e7gz.booking.internal.LateConfirmDb.reservations;
import static ps.e7gz.booking.internal.LateConfirmDb.t2;
import static ps.e7gz.booking.internal.LateConfirmDb.t3;
import static ps.e7gz.booking.internal.LateConfirmIntegrationTest.ok;
import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.bookingStart;
import static ps.e7gz.booking.internal.VenueAnswerDb.count;
import static ps.e7gz.booking.internal.VenueAnswerDb.events;
import static ps.e7gz.booking.internal.VenueAnswerDb.history;
import static ps.e7gz.booking.internal.VenueAnswerDb.job;
import static ps.e7gz.booking.internal.VenueAnswerDb.outboxTypes;
import static ps.e7gz.booking.internal.VenueAnswerDb.t1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import ps.e7gz.booking.internal.BookingStates.Plant;
import ps.e7gz.booking.internal.LateConfirmDb.Attempt;
import ps.e7gz.booking.internal.VenueAnswerDb.HistoryRow;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmExpiryHandler;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmWarningHandler;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmations;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.CommandAnswer;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.Boundary;
import ps.e7gz.support.boundary.Deadlines;
import ps.e7gz.support.boundary.DueJobs;
import ps.e7gz.support.boundary.TestCommands;
import ps.e7gz.venue.api.HoursChangeBookings;

/**
 * E7-113 Phase 3B - {@link HoursChangeBookings#cancel}: the Booking side of an opening-hours change (FR-VEN-23 step 8,
 * FR-VEN-24, Spec v5.0 section 14.8) on bookings made by the real commands, each cancellation one committed command
 * under the lock set the caller must hold (the venue, the bookings' Resources and Customers), so the deferred integrity
 * triggers - the Booking Fee decision, the transition guard - run at COMMIT. The Venue command that will call it is
 * Phase 4; nothing here goes through it. Phase 3C adds AWAITING_CUSTOMER_CONFIRMATION: the Late Confirmation closed
 * through the Phase 3A port in the same command, so {@code tr_late_confirmation_integrity} runs at COMMIT too.
 */
class HoursChangeCancellationIntegrationTest extends IntegrationTest {

    private static final String REASON = "VENUE_OPENING_HOURS_CHANGED";
    private static final String AWAITING = BookingStatus.AWAITING_CUSTOMER_CONFIRMATION.name();

    /** Every Late Confirm integration event: an hours change writes none of them (FR-VEN-28: BookingCancelled only). */
    private static final List<String> LATE_CONFIRM_EVENTS = List.of(IntegrationEvents.LATE_CONFIRM_ACCEPTED,
            IntegrationEvents.LATE_CONFIRM_DECLINED, IntegrationEvents.LATE_CONFIRM_TIMED_OUT,
            IntegrationEvents.LATE_CONFIRM_WARNING_DUE);

    @Autowired
    ApplicationContext context;

    @Autowired
    CommandRunner runner;

    @Autowired
    HoursChangeBookings port;

    @Autowired
    OnlineBookingCreation creation;

    @Autowired
    VenueResponses responses;

    @Autowired
    VenueAnswers answers;

    @Autowired
    VenueAnswerStore store;

    @Autowired
    ManualBookings manualBookings;

    @Autowired
    LateConfirmations lateConfirmations;

    @Autowired
    ObjectMapper json;

    private CorrelationId.Scope correlation;

    @BeforeEach
    void openCorrelation() {
        correlation = CorrelationId.open(UUID.randomUUID());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    // ---------------------------------------------------------------- one booking of each kind

    @Test
    void aPendingOnlineBookingIsCancelledReleasedRefundedAndItsT1Superseded() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking pending = pending(world, world.start());
        assertThat(job(t1(pending.bookingId())).orElseThrow().status()).isEqualTo("PENDING");
        List<String> typesBefore = outboxTypes(pending.bookingId());

        Instant at = cancel(world, pending.bookingId());

        VenueAnswerDb.BookingRow row = booking(pending.bookingId());
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.terminalReason()).isEqualTo(REASON);
        assertThat(row.terminalAt()).isEqualTo(at);
        assertThat(row.feeTreatment()).isEqualTo("FULL_REFUND");
        assertThat(row.counted()).as("the commitment slot is free").isFalse();
        assertCancelledBy(world, pending.bookingId(), BookingStatus.PENDING, at);
        assertReleased(pending.bookingId(), at);
        assertRefunded(pending.bookingId(), pending.customerId());
        assertThat(job(t1(pending.bookingId())).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertOnlyBookingCancelledAdded(pending.bookingId(), typesBefore);
        JsonNode event = onlyCancelledEvent(pending.bookingId());
        assertThat(event.path("terminalReason").asText()).isEqualTo(REASON);
        assertThat(event.path("customerId").asText()).isEqualTo(pending.customerId().toString());
        assertThat(event.path("venueId").asText()).isEqualTo(world.venue.id().toString());
        assertThat(event.path("resourceId").asText()).isEqualTo(world.room.id().toString());
        assertThat(event.path("startAt").asText()).isEqualTo(pending.startAt().toString());
        assertThat(event.path("endAt").asText()).isEqualTo(pending.endAt().toString());
        assertThat(event.has("source")).as("an Online event carries no source, as every Online one").isFalse();
    }

    @Test
    void aPausedPendingBookingEndsItsPauseAtTheCancellationInstant() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking pending = pending(world, world.start());
        VenueAnswerDb.venueEmergency(world.venue.id());
        VenueAnswerDb.paused(pending.bookingId());
        assertThat(job(t1(pending.bookingId())).orElseThrow().status()).isEqualTo("PAUSED");
        Instant pausedAt = OWNER.sql("SELECT response_pause_started_at FROM booking.booking WHERE id = :id")
                .param("id", pending.bookingId()).query(Timestamp.class).single().toInstant();

        Instant at = cancel(world, pending.bookingId());

        assertThat(booking(pending.bookingId()).status()).isEqualTo("CANCELLED");
        assertThat(VenueAnswerDb.responsePaused(pending.bookingId())).isFalse();
        Map<String, Object> pause = OWNER.sql("""
                        SELECT h.pause_ended_at,
                               h.accumulated_duration = b.cumulative_emergency_pause_duration AS same_total,
                               b.cumulative_emergency_pause_duration
                                   = CAST(:at AS timestamptz) - CAST(:pausedAt AS timestamptz) AS whole
                          FROM operations.booking_timer_pause_history h
                          JOIN booking.booking b ON b.id = h.booking_id
                         WHERE h.booking_id = :id AND h.pause_kind = 'VENUE_RESPONSE'""")
                .param("id", pending.bookingId())
                .param("at", Timestamp.from(at)).param("pausedAt", Timestamp.from(pausedAt))
                .query().singleRow();
        assertThat(((Timestamp) pause.get("pause_ended_at")).toInstant()).isEqualTo(at);
        assertThat(pause.get("same_total")).as("the history row closes with the booking's cumulative pause")
                .isEqualTo(true);
        assertThat(pause.get("whole"))
                .as("the booking's pause, from its start to the cancellation, added to a zero total")
                .isEqualTo(true);
        assertThat(job(t1(pending.bookingId())).orElseThrow().status()).as("the PAUSED T1 is withdrawn")
                .isEqualTo("SUPERSEDED");
        assertReleased(pending.bookingId(), at);
        assertRefunded(pending.bookingId(), pending.customerId());
        VenueAnswerDb.endEmergencies(world.venue.id());
    }

    /** D2: still PENDING in the database - T1 has not run - so it is cancelled, never expired here. */
    @Test
    void aPendingBookingPastItsResponseDeadlineWhoseT1HasNotRunIsCancelled() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking pending = pending(world, world.start());
        Instant deadline = runner.run(TestCommands.under(scope(pending.bookingId()), context -> {
            Deadlines.move(context, "booking.booking.venue_response_expires_at", pending.bookingId(),
                    context.commandNow());
            return context.commandNow();
        }));
        Deadlines.awaitDatabaseClockPast(deadline.plusSeconds(1));
        assertThat(job(t1(pending.bookingId())).orElseThrow().status()).as("T1 has not run").isEqualTo("PENDING");

        Instant at = cancel(world, pending.bookingId());

        assertThat(at).isAfter(deadline);
        assertThat(booking(pending.bookingId()).status()).isEqualTo("CANCELLED");
        assertThat(history(pending.bookingId())).extracting(HistoryRow::toStatus)
                .as("no expiry is written").containsExactly("PENDING", "CANCELLED");
        assertCancelledBy(world, pending.bookingId(), BookingStatus.PENDING, at);
        assertReleased(pending.bookingId(), at);
        assertRefunded(pending.bookingId(), pending.customerId());
        assertThat(job(t1(pending.bookingId())).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(events(IntegrationEvents.VENUE_RESPONSE_EXPIRED, pending.bookingId())).isEmpty();
        assertThat(onlyCancelledEvent(pending.bookingId()).path("terminalReason").asText()).isEqualTo(REASON);
    }

    @Test
    void aConfirmedOnlineBookingIsCancelledAndItsT4Superseded() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking confirmed = confirmed(world, world.start());
        assertThat(job(bookingStart(confirmed.bookingId())).orElseThrow().status()).isEqualTo("PENDING");
        List<String> typesBefore = outboxTypes(confirmed.bookingId());

        Instant at = cancel(world, confirmed.bookingId());

        VenueAnswerDb.BookingRow row = booking(confirmed.bookingId());
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.terminalReason()).isEqualTo(REASON);
        assertThat(row.feeTreatment()).isEqualTo("FULL_REFUND");
        assertCancelledBy(world, confirmed.bookingId(), BookingStatus.CONFIRMED, at);
        assertReleased(confirmed.bookingId(), at);
        assertRefunded(confirmed.bookingId(), confirmed.customerId());
        assertThat(job(bookingStart(confirmed.bookingId())).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(job(ManualBookingCommand.bookingCompleteKey(confirmed.bookingId())))
                .as("a CONFIRMED booking has no T5 yet; none is invented").isEmpty();
        assertOnlyBookingCancelledAdded(confirmed.bookingId(), typesBefore);
    }

    @Test
    void aConfirmedManualBookingInsideAnOperatingSessionIsCancelledWithoutARefundDecision() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID manual = manual(world, world.start());
        UUID session = operatingSession(manual);
        assertThat(session).as("made inside published hours").isNotNull();
        assertThat(job(bookingStart(manual)).orElseThrow().status()).isEqualTo("PENDING");
        List<String> typesBefore = outboxTypes(manual);

        Instant at = cancel(world, manual);

        VenueAnswerDb.BookingRow row = booking(manual);
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.terminalReason()).isEqualTo(REASON);
        assertThat(row.feeTreatment()).as("the database's own reason map").isEqualTo("FULL_REFUND");
        assertThat(operatingSession(manual)).as("the session snapshot is never rewritten").isEqualTo(session);
        assertCancelledBy(world, manual, BookingStatus.CONFIRMED, at);
        assertReleased(manual, at);
        assertThat(count("loyalty.booking_fee_refund WHERE booking_id = :id", manual)).isZero();
        assertThat(job(bookingStart(manual)).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertOnlyBookingCancelledAdded(manual, typesBefore);
        JsonNode event = onlyCancelledEvent(manual);
        assertThat(event.path("terminalReason").asText()).isEqualTo(REASON);
        assertThat(event.path("source").asText()).isEqualTo("MANUAL");
        assertThat(event.path("customerId").isNull()).isTrue();
        assertThat(event.path("bookingId").asText()).isEqualTo(manual.toString());
    }

    // ---------------------------------------------------------------- one command, several customers

    @Test
    void bookingsOfSeveralCustomersAreCancelledInOneCommandAndUnaffectedOnesStay() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID otherCustomer = world.anotherCustomer();
        Instant start = world.start();
        CreatedBooking firstPending = pending(world, start);
        CreatedBooking otherPending = creation.create(world.request(otherCustomer, world.room,
                start.plus(Duration.ofHours(2)), 60));
        CreatedBooking firstConfirmed = confirmed(world, start.plus(Duration.ofHours(4)));
        UUID manual = manual(world, start.plus(Duration.ofHours(6)));
        CreatedBooking unaffected = creation.create(world.request(otherCustomer, world.room,
                start.plus(Duration.ofHours(8)), 60));
        List<UUID> online = List.of(firstPending.bookingId(), otherPending.bookingId(), firstConfirmed.bookingId());
        List<UUID> affected = new ArrayList<>(online);
        affected.add(manual);
        int unaffectedHistory = history(unaffected.bookingId()).size();
        List<String> unaffectedTypes = outboxTypes(unaffected.bookingId());

        Instant at = runner.run(TestCommands.under(scope(concat(affected, unaffected.bookingId())), context -> {
            port.cancel(context, world.venue.id(), world.account.id(), affected);
            return context.commandNow();
        }));

        for (UUID id : affected) {
            assertThat(booking(id).status()).as("booking %s", id).isEqualTo("CANCELLED");
            assertThat(booking(id).terminalAt()).as("one instant for the whole command").isEqualTo(at);
            assertReleased(id, at);
            assertThat(events(IntegrationEvents.BOOKING_CANCELLED, id)).as("booking %s", id).hasSize(1);
        }
        assertCancelledBy(world, firstPending.bookingId(), BookingStatus.PENDING, at);
        assertCancelledBy(world, otherPending.bookingId(), BookingStatus.PENDING, at);
        assertCancelledBy(world, firstConfirmed.bookingId(), BookingStatus.CONFIRMED, at);
        assertCancelledBy(world, manual, BookingStatus.CONFIRMED, at);
        assertRefunded(firstPending.bookingId(), world.customer.id());
        assertRefunded(otherPending.bookingId(), otherCustomer);
        assertRefunded(firstConfirmed.bookingId(), world.customer.id());
        assertThat(count("loyalty.booking_fee_refund WHERE booking_id = :id", manual)).isZero();
        assertThat(OWNER.sql("""
                        SELECT count(DISTINCT correlation_id) FROM loyalty.booking_fee_refund
                         WHERE booking_id IN (:ids)""")
                .param("ids", online).query(Long.class).single()).as("one command, one correlation").isEqualTo(1L);

        assertThat(booking(unaffected.bookingId()).status()).as("not in the set: never cancelled").isEqualTo("PENDING");
        assertThat(activeReservation(unaffected.bookingId())).isTrue();
        assertThat(history(unaffected.bookingId())).hasSize(unaffectedHistory);
        assertThat(outboxTypes(unaffected.bookingId())).isEqualTo(unaffectedTypes);
        assertThat(job(t1(unaffected.bookingId())).orElseThrow().status()).isEqualTo("PENDING");
        assertThat(count("loyalty.booking_fee_refund WHERE booking_id = :id", unaffected.bookingId())).isZero();
    }

    // ---------------------------------------------------------------- refused: the whole command rolls back

    @Test
    void anUnknownIdFailsTheWholeCommand() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking valid = pending(world, world.start());
        assertRefusedAndNothingChanged(world, valid, UUID.randomUUID(), "no such booking");
    }

    @Test
    void anotherVenuesBookingFailsTheWholeCommand() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking valid = pending(world, world.start());
        OnlineBookingWorld elsewhere = OnlineBookingWorld.create();
        CreatedBooking foreign = pending(elsewhere, elsewhere.start());
        assertRefusedAndNothingChanged(world, valid, foreign.bookingId(), "is not of venue");
        assertThat(booking(foreign.bookingId()).status()).isEqualTo("PENDING");
        assertThat(activeReservation(foreign.bookingId())).isTrue();
    }

    @Test
    void anActiveBookingFailsTheWholeCommand() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking valid = pending(world, world.start());
        CreatedBooking active = pending(world, world.start().plus(Duration.ofHours(2)));
        BookingStates.plant(active.bookingId(), Plant.of("ACTIVE"));
        assertRefusedAndNothingChanged(world, valid, active.bookingId(), "ACTIVE ONLINE booking is not cancelled");
        assertThat(booking(active.bookingId()).status()).isEqualTo("ACTIVE");
    }

    @Test
    void aVenueResponseExpiredBookingFailsTheWholeCommand() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking valid = pending(world, world.start());
        UUID expired = new LateConfirmDb(runner, creation, store, answers)
                .expired(world, world.start().plus(Duration.ofHours(2)), 60);
        assertRefusedAndNothingChanged(world, valid, expired, "VENUE_RESPONSE_EXPIRED ONLINE booking");
        assertThat(booking(expired).status()).isEqualTo("VENUE_RESPONSE_EXPIRED");
    }

    @Test
    void aManualBookingMadeOutsidePublishedHoursFailsTheWholeCommand() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking valid = pending(world, world.start());
        UUID outside = manual(world, world.t0.plus(Duration.ofHours(11)).truncatedTo(ChronoUnit.MINUTES));
        assertThat(operatingSession(outside)).as("made outside every session (FR-MAN-03)").isNull();
        assertRefusedAndNothingChanged(world, valid, outside, "outside an Operating Session");
        assertThat(booking(outside).status()).isEqualTo("CONFIRMED");
        assertThat(activeReservation(outside)).isTrue();
    }

    @Test
    void aBookingThatDoesNotStartAfterTheCommandInstantFailsTheWholeCommand() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking valid = pending(world, world.start());
        CreatedBooking started = creation.create(world.request(world.anotherCustomer(), world.room,
                world.start().plus(Duration.ofHours(2)), 60));
        BookingStates.plant(started.bookingId(), Plant.of("PENDING").startAt(OnlineBookingWorld.dbNow()));
        assertRefusedAndNothingChanged(world, valid, started.bookingId(), "does not start after");
        assertThat(booking(started.bookingId()).status()).isEqualTo("PENDING");
    }

    /** The lock set without the Customer: the Loyalty batch is refused by the database and nothing is kept. */
    @Test
    void aCustomerOutsideTheLockSetIsRefusedByTheDatabaseAndNothingIsKept() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking pending = pending(world, world.start());
        UUID manual = manual(world, world.start().plus(Duration.ofHours(2)));
        CommandScope withoutCustomer = CommandScope.shared(LockSet.builder().venues(world.venue.id())
                .resources(world.room.id()).build());

        assertThatThrownBy(() -> runner.run(TestCommands.under(withoutCustomer, context -> {
            port.cancel(context, world.venue.id(), world.account.id(), List.of(pending.bookingId(), manual));
            return null;
        }))).hasStackTraceContaining("All Loyalty Users must be prelocked in the shared scope");

        assertUnchanged(pending);
        assertThat(booking(manual).status()).as("the Manual cancellation rolls back with it").isEqualTo("CONFIRMED");
        assertThat(activeReservation(manual)).isTrue();
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, manual)).isEmpty();
        assertThat(job(bookingStart(manual)).orElseThrow().status()).isEqualTo("PENDING");
    }

    @Test
    void anEmptyBatchWritesNothing() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking pending = pending(world, world.start());
        long outboxBefore = outboxRows(List.of(pending.bookingId()));

        runner.run(TestCommands.under(scope(List.of(pending.bookingId())), context -> {
            port.cancel(context, world.venue.id(), world.account.id(), List.of());
            return null;
        }));

        assertThat(outboxRows(List.of(pending.bookingId()))).isEqualTo(outboxBefore);
        assertUnchanged(pending);
    }

    @Test
    void aRepeatedOrNullIdIsRefusedBeforeAnyWrite() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking pending = pending(world, world.start());
        CommandScope scope = scope(List.of(pending.bookingId()));

        assertThatThrownBy(() -> runner.run(TestCommands.under(scope, context -> {
            port.cancel(context, world.venue.id(), world.account.id(),
                    List.of(pending.bookingId(), pending.bookingId()));
            return null;
        }))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("appears twice");
        List<UUID> withNull = new ArrayList<>();
        withNull.add(pending.bookingId());
        withNull.add(null);
        assertThatThrownBy(() -> runner.run(TestCommands.under(scope, context -> {
            port.cancel(context, world.venue.id(), world.account.id(), withNull);
            return null;
        }))).isInstanceOf(NullPointerException.class);

        assertUnchanged(pending);
    }

    // ---------------------------------------------------------------- a T1 a worker holds

    @Test
    void aLeasedT1IsLeftToItsWorkerWhichThenFindsTheBookingNoLongerPending() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking pending = pending(world, world.start());
        leased(t1(pending.bookingId()));

        cancel(world, pending.bookingId());

        assertThat(job(t1(pending.bookingId())).orElseThrow().status()).as("never taken from its lease holder")
                .isEqualTo("LEASED");
        JobOutcome fired = runner.run(TestCommands.under(scope(pending.bookingId()), context ->
                answers.expire(context, new ScheduledJob(UUID.randomUUID(),
                        CreateOnlineBookingCommand.VENUE_RESPONSE_EXPIRY, CreateOnlineBookingCommand.BOOKING_SUBJECT,
                        pending.bookingId(), context.commandNow(), 1, t1(pending.bookingId()),
                        CorrelationId.generate()))));
        assertThat(fired).isInstanceOf(JobOutcome.Superseded.class);
        assertThat(booking(pending.bookingId()).status()).isEqualTo("CANCELLED");
        assertThat(booking(pending.bookingId()).terminalReason()).isEqualTo(REASON);
        assertThat(events(IntegrationEvents.VENUE_RESPONSE_EXPIRED, pending.bookingId())).isEmpty();
    }

    // ---------------------------------------------------------------- Phase 3C: AWAITING_CUSTOMER_CONFIRMATION

    /**
     * FR-VEN-28: the Late Confirmation timer stops and the hold is released in the same command that cancels the
     * booking - no Customer response is invented, no Late Confirm event is written, one BookingCancelled.
     */
    @Test
    void anAwaitingBookingHasItsLateConfirmationClosedAndIsCancelledByTheVenue() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world, world.customer.id(), world.start());
        Attempt open = attempt(late).orElseThrow();
        assertThat(job(t2(late)).orElseThrow().status()).isEqualTo("PENDING");
        assertThat(job(t3(late)).orElseThrow().status()).as("a five-minute window has a warning").isEqualTo("PENDING");
        List<String> typesBefore = outboxTypes(late);

        Instant at = cancel(world, late);

        VenueAnswerDb.BookingRow row = booking(late);
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.terminalReason()).isEqualTo(REASON);
        assertThat(row.terminalAt()).isEqualTo(at);
        assertThat(row.feeTreatment()).isEqualTo("FULL_REFUND");
        assertThat(row.counted()).as("the commitment slot is free").isFalse();
        assertCancelledBy(world, late, BookingStatus.AWAITING_CUSTOMER_CONFIRMATION, at);
        assertLateConfirmationClosed(late, open, at);
        assertThat(LateConfirmDb.warningSentAt(late)).as("never warned, and none is invented").isNull();
        assertThat(job(t2(late)).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(job(t3(late)).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(reservations(late)).filteredOn(r -> r.type().equals("BOOKING")).singleElement()
                .satisfies(reservation -> {
                    assertThat(reservation.active()).as("released at the venue's expiry, long before").isFalse();
                    assertThat(reservation.releaseReason()).isEqualTo("VENUE_RESPONSE_EXPIRED");
                });
        assertRefundedOnTheCancellation(late, world.customer.id());
        assertOnlyBookingCancelledAdded(late, typesBefore);
        LATE_CONFIRM_EVENTS.forEach(type -> assertThat(events(type, late)).as(type).isEmpty());
        JsonNode event = onlyCancelledEvent(late);
        assertThat(event.path("terminalReason").asText()).isEqualTo(REASON);
        assertThat(event.path("customerId").asText()).isEqualTo(world.customer.id().toString());
        assertThat(event.has("source")).as("an Online event carries no source").isFalse();
        assertLateConfirmIntegrityHolds(late);
    }

    /** C3 (provisional): the pause ends at the command instant; the attempt's deadline stays as recorded. */
    @Test
    void aPausedAwaitingBookingEndsItsLateConfirmPauseAtTheCancellationInstant() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world, world.customer.id(), world.start());
        LateConfirmDb.lateConfirmPaused(late);
        assertThat(job(t2(late)).orElseThrow().status()).isEqualTo("PAUSED");
        Attempt open = attempt(late).orElseThrow();
        Instant pausedAt = OWNER.sql("SELECT late_confirm_pause_started_at FROM booking.booking WHERE id = :id")
                .param("id", late).query(Timestamp.class).single().toInstant();

        Instant at = cancel(world, late);

        assertThat(booking(late).status()).isEqualTo("CANCELLED");
        assertThat(LateConfirmDb.openLateConfirmPauses(late)).isZero();
        Map<String, Object> pause = OWNER.sql("""
                        SELECT b.late_confirm_pause_started_at IS NULL AS ended, h.pause_ended_at,
                               EXTRACT(EPOCH FROM b.cumulative_late_confirm_emergency_pause_duration) AS cumulative,
                               h.accumulated_duration = b.cumulative_late_confirm_emergency_pause_duration AS same_total,
                               b.cumulative_late_confirm_emergency_pause_duration
                                   = CAST(:at AS timestamptz) - CAST(:pausedAt AS timestamptz) AS whole
                          FROM operations.booking_timer_pause_history h
                          JOIN booking.booking b ON b.id = h.booking_id
                         WHERE h.booking_id = :id AND h.pause_kind = 'LATE_CONFIRM'""")
                .param("id", late).param("at", Timestamp.from(at)).param("pausedAt", Timestamp.from(pausedAt))
                .query().singleRow();
        assertThat(pause.get("ended")).isEqualTo(true);
        assertThat(((Timestamp) pause.get("pause_ended_at")).toInstant()).isEqualTo(at);
        assertThat((BigDecimal) pause.get("cumulative")).as("FR-OPS-37: added to the counter").isPositive();
        assertThat(pause.get("same_total")).as("FR-OPS-41: the row closes with the booking's cumulative pause")
                .isEqualTo(true);
        assertThat(pause.get("whole")).as("the pause, from its start to the cancellation, added to a zero total")
                .isEqualTo(true);
        assertThat(job(t2(late)).orElseThrow().status()).as("a PAUSED T2 is withdrawn").isEqualTo("SUPERSEDED");
        assertThat(job(t3(late)).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertLateConfirmationClosed(late, open, at);
        assertCancelledBy(world, late, BookingStatus.AWAITING_CUSTOMER_CONFIRMATION, at);
        assertRefundedOnTheCancellation(late, world.customer.id());
        assertLateConfirmIntegrityHolds(late);
    }

    @Test
    void anAwaitingBookingAlreadyWarnedKeepsItsWarningAndItsFinishedT3() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world, world.customer.id(), world.start());
        LateConfirmDb.deadlineAt(late, OnlineBookingWorld.dbNow().plusSeconds(30));
        fireNow(late, t3(late));
        Attempt open = attempt(late).orElseThrow();
        Instant warned = LateConfirmDb.warningSentAt(late);
        assertThat(warned).isNotNull();
        assertThat(job(t3(late)).orElseThrow().status()).isEqualTo("DONE");

        Instant at = cancel(world, late);

        assertLateConfirmationClosed(late, open, at);
        assertThat(LateConfirmDb.warningSentAt(late)).as("as T3 recorded it").isEqualTo(warned);
        assertThat(job(t3(late)).orElseThrow().status()).as("a DONE job is never superseded").isEqualTo("DONE");
        assertThat(job(t2(late)).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(events(IntegrationEvents.LATE_CONFIRM_WARNING_DUE, late)).as("only T3's own").hasSize(1);
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, late)).hasSize(1);
        assertLateConfirmIntegrityHolds(late);
    }

    @Test
    void aLeasedT2IsLeftToItsWorkerWhichThenFindsTheBookingCancelled() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world, world.customer.id(), world.start());
        leased(t2(late));

        Instant at = cancel(world, late);

        assertThat(job(t2(late)).orElseThrow().status()).as("never taken from its lease holder").isEqualTo("LEASED");
        assertThat(job(t3(late)).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(fireDirectly(late, "LATE_CONFIRM_EXPIRY")).isInstanceOf(JobOutcome.Superseded.class);
        assertThat(fireDirectly(late, "LATE_CONFIRM_WARNING")).isInstanceOf(JobOutcome.Superseded.class);
        VenueAnswerDb.BookingRow row = booking(late);
        assertThat(row.terminalReason()).as("no second terminal outcome").isEqualTo(REASON);
        assertThat(row.terminalAt()).isEqualTo(at);
        assertThat(attempt(late).orElseThrow().response()).as("no TIMED_OUT response").isNull();
        assertThat(history(late)).filteredOn(h -> "CANCELLED".equals(h.toStatus())).hasSize(1);
        assertThat(count("loyalty.booking_fee_refund WHERE booking_id = :id", late)).isEqualTo(1);
        LATE_CONFIRM_EVENTS.forEach(type -> assertThat(events(type, late)).as(type).isEmpty());
    }

    /**
     * FR-AVL-15 (Amr's E7-113 decision 1, replacing C2 Option A): the stored Late Confirm deadline is authoritative. At
     * and one second past it, an AWAITING booking whose window is not paused belongs to T2 even though T2 has not run:
     * the port refuses it as an uncancellable id (D3) and the whole command rolls back - the booking stays AWAITING
     * with its open attempt, its hold and its T2 for the worker, and nothing is written.
     */
    @ParameterizedTest
    @EnumSource(value = Boundary.class, names = {"AT", "ONE_SECOND_AFTER"})
    void anAwaitingBookingAtOrPastItsDeadlineBelongsToT2AndIsNeverCancelledByTheHoursChange(Boundary side) {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world, world.customer.id(), world.start());
        Attempt initiated = deadlineMovable(late);
        List<String> typesBefore = outboxTypes(late);
        List<HistoryRow> historyBefore = history(late);

        assertThatThrownBy(() -> runner.run(TestCommands.under(scope(late), command -> {
            Deadlines.moveTo(command, side, "booking.late_confirmation.expires_at", initiated.id());
            port.cancel(command, world.venue.id(), world.account.id(), List.of(late));
            return command.commandNow();
        }))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the Late Confirm deadline has passed: T2 owns the booking (FR-AVL-15)");

        assertThat(booking(late).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(history(late)).isEqualTo(historyBefore);
        Attempt open = attempt(late).orElseThrow();
        assertThat(open.closedAt()).isNull();
        assertThat(open.response()).isNull();
        assertThat(job(t2(late)).orElseThrow().status()).as("T2 keeps the booking").isEqualTo("PENDING");
        assertThat(count("loyalty.booking_fee_refund WHERE booking_id = :id", late)).isZero();
        assertThat(outboxTypes(late)).isEqualTo(typesBefore);
        assertThat(reservations(late)).filteredOn(r -> r.id().equals(open.holdId()))
                .singleElement().satisfies(hold -> assertThat(hold.active()).isTrue());
        assertLateConfirmIntegrityHolds(late);
    }

    /**
     * The other side of the boundary: one second before the stored deadline the window is still open, so the hours
     * change cancels the AWAITING booking - no timeout is synthesised, T2 is superseded.
     */
    @Test
    void anAwaitingBookingOneSecondBeforeItsDeadlineIsCancelledByTheHoursChange() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world, world.customer.id(), world.start());
        Attempt initiated = deadlineMovable(late);
        List<String> typesBefore = outboxTypes(late);

        Instant[] deadline = new Instant[1];
        Instant at = runner.run(TestCommands.under(scope(late), command -> {
            deadline[0] = Deadlines.moveTo(command, Boundary.ONE_SECOND_BEFORE, "booking.late_confirmation.expires_at",
                    initiated.id());
            port.cancel(command, world.venue.id(), world.account.id(), List.of(late));
            return command.commandNow();
        }));

        assertThat(at).isEqualTo(Boundary.ONE_SECOND_BEFORE.now(deadline[0]));
        assertThat(booking(late).status()).isEqualTo("CANCELLED");
        assertThat(booking(late).terminalReason()).as("never CUSTOMER_RECONFIRM_TIMEOUT here").isEqualTo(REASON);
        assertCancelledBy(world, late, BookingStatus.AWAITING_CUSTOMER_CONFIRMATION, at);
        Attempt closed = attempt(late).orElseThrow();
        assertThat(closed.closedAt()).isEqualTo(at);
        assertThat(closed.response()).as("no TIMED_OUT is synthesised").isNull();
        assertThat(closed.expiresAt()).as("the deadline stays as recorded").isEqualTo(deadline[0]);
        assertThat(job(t2(late)).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertRefundedOnTheCancellation(late, world.customer.id());
        assertOnlyBookingCancelledAdded(late, typesBefore);
        LATE_CONFIRM_EVENTS.forEach(type -> assertThat(events(type, late)).as(type).isEmpty());
        assertLateConfirmIntegrityHolds(late);
    }

    /**
     * The stored deadline only moves later (guard_late_confirmation_mutation): put it just after the initiation and wait
     * until a deadline one second before "now" is still after the initiation, so a command can move it to its own
     * instant's boundary. T2 has not run.
     */
    private static Attempt deadlineMovable(UUID late) {
        Attempt initiated = attempt(late).orElseThrow();
        LateConfirmDb.deadlineAt(late, initiated.initiatedAt().plusMillis(1));
        Deadlines.awaitDatabaseClockPast(initiated.initiatedAt().plusSeconds(2));
        assertThat(job(t2(late)).orElseThrow().status()).as("T2 has not run").isEqualTo("PENDING");
        return initiated;
    }

    /**
     * One command, every kind the hours change cancels: PENDING, CONFIRMED and AWAITING Online Bookings of two
     * Customers and a CONFIRMED Manual Booking, all of the venue - one instant, one Loyalty batch, one correlation.
     */
    @Test
    void aMixedBatchWithAnAwaitingBookingIsCancelledInOneCommand() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID otherCustomer = world.anotherCustomer();
        Instant start = world.start();
        CreatedBooking firstPending = pending(world, start);
        CreatedBooking firstConfirmed = confirmed(world, start.plus(Duration.ofHours(2)));
        UUID otherAwaiting = awaiting(world, otherCustomer, start.plus(Duration.ofHours(4)));
        UUID manual = manual(world, start.plus(Duration.ofHours(6)));
        Attempt open = attempt(otherAwaiting).orElseThrow();
        List<UUID> affected = List.of(firstPending.bookingId(), firstConfirmed.bookingId(), otherAwaiting, manual);
        // C-3: another AWAITING booking of the same venue, with its own open attempt and active hold - NOT in the set
        UUID bystander = awaiting(world, world.customer.id(), start.plus(Duration.ofHours(8)));
        assertThat(affected).doesNotContain(bystander);
        Committed bystanderBefore = committed(bystander);
        assertThat(bystanderBefore.booking().status()).isEqualTo(AWAITING);
        assertThat(bystanderBefore.attempt().closedAt()).as("a real open attempt").isNull();
        assertThat(bystanderBefore.reservations()).filteredOn(r -> r.id().equals(bystanderBefore.attempt().holdId()))
                .singleElement().satisfies(hold -> {
                    assertThat(hold.type()).isEqualTo("LATE_CONFIRM_HOLD");
                    assertThat(hold.active()).as("its hold is active").isTrue();
                });
        assertThat(bystanderBefore.t2()).isEqualTo("PENDING");
        assertThat(bystanderBefore.t3()).isEqualTo("PENDING");
        for (UUID id : affected) {
            assertThat(OWNER.sql("SELECT venue_id FROM booking.booking WHERE id = :id").param("id", id)
                    .query(UUID.class).single()).as("booking %s is of the command's venue", id)
                    .isEqualTo(world.venue.id());
        }

        Instant at = runner.run(TestCommands.under(scope(affected), context -> {
            port.cancel(context, world.venue.id(), world.account.id(), affected);
            return context.commandNow();
        }));

        for (UUID id : affected) {
            assertThat(booking(id).status()).as("booking %s", id).isEqualTo("CANCELLED");
            assertThat(booking(id).terminalReason()).as("booking %s", id).isEqualTo(REASON);
            assertThat(booking(id).terminalAt()).as("one instant for the whole command").isEqualTo(at);
            assertThat(events(IntegrationEvents.BOOKING_CANCELLED, id)).as("booking %s", id).hasSize(1);
        }
        assertCancelledBy(world, firstPending.bookingId(), BookingStatus.PENDING, at);
        assertCancelledBy(world, firstConfirmed.bookingId(), BookingStatus.CONFIRMED, at);
        assertCancelledBy(world, otherAwaiting, BookingStatus.AWAITING_CUSTOMER_CONFIRMATION, at);
        assertCancelledBy(world, manual, BookingStatus.CONFIRMED, at);
        assertReleased(firstPending.bookingId(), at);
        assertReleased(firstConfirmed.bookingId(), at);
        assertReleased(manual, at);
        assertLateConfirmationClosed(otherAwaiting, open, at);
        assertThat(job(t1(firstPending.bookingId())).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(job(bookingStart(firstConfirmed.bookingId())).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(job(bookingStart(manual)).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(job(t2(otherAwaiting)).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(job(t3(otherAwaiting)).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertRefunded(firstPending.bookingId(), world.customer.id());
        assertRefunded(firstConfirmed.bookingId(), world.customer.id());
        assertRefundedOnTheCancellation(otherAwaiting, otherCustomer);
        assertThat(count("loyalty.booking_fee_refund WHERE booking_id = :id", manual)).isZero();
        List<UUID> online = List.of(firstPending.bookingId(), firstConfirmed.bookingId(), otherAwaiting);
        assertThat(OWNER.sql("""
                        SELECT count(*) AS rows, count(DISTINCT correlation_id) AS correlations,
                               count(DISTINCT user_id) AS customers
                          FROM loyalty.booking_fee_refund WHERE booking_id IN (:ids)""")
                .param("ids", online).query().singleRow())
                .as("one Loyalty batch: three decisions, two Customers, one correlation")
                .containsEntry("rows", 3L).containsEntry("correlations", 1L).containsEntry("customers", 2L);
        LATE_CONFIRM_EVENTS.forEach(type -> assertThat(events(type, otherAwaiting)).as(type).isEmpty());
        assertLateConfirmIntegrityHolds(otherAwaiting);

        // C-3: the bystander, never named, keeps every committed fact - status, history, attempt (open, deadline and
        // warning as recorded), active hold, T2/T3, events of it and of its hold, and no refund decision
        Committed bystanderAfter = committed(bystander);
        assertThat(bystanderAfter).as("the AWAITING booking outside the set").isEqualTo(bystanderBefore);
        assertThat(bystanderAfter.booking().status()).isEqualTo(AWAITING);
        assertThat(bystanderAfter.booking().terminalReason()).isNull();
        assertThat(bystanderAfter.history()).extracting(HistoryRow::toStatus).doesNotContain("CANCELLED");
        assertThat(bystanderAfter.attempt().closedAt()).isNull();
        assertThat(bystanderAfter.reservations()).filteredOn(r -> r.id().equals(bystanderAfter.attempt().holdId()))
                .singleElement().satisfies(hold -> {
                    assertThat(hold.active()).isTrue();
                    assertThat(hold.releasedAt()).isNull();
                });
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, bystander)).isEmpty();
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, bystanderAfter.attempt().holdId())).isEmpty();
        assertThat(bystanderAfter.refunds()).isEmpty();
        assertLateConfirmIntegrityHolds(bystander);
    }

    /**
     * C-2: a booking T2 has already cancelled - CUSTOMER_RECONFIRM_TIMEOUT, committed by the real Late Confirm expiry
     * worker - is no longer AWAITING, so the hours change refuses it (a caller defect, IllegalStateException) and
     * writes nothing: no second terminal outcome, history row, refund decision or event. C2's Option A covers only a
     * booking still stored AWAITING; the Venue command's refreshed affected set (409) is Phase 4's.
     */
    @Test
    void aBookingT2HasAlreadyTimedOutIsRefusedAndKeepsItsTimeout() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world, world.customer.id(), world.start());
        // the stored deadline just after the initiation (the fixture of a window that has run out), then the REAL
        // T2 - the LateConfirmExpiryHandler, leased and fired by the worker - times the booking out and commits
        LateConfirmDb.deadlinePassed(late);
        fireNow(late, t2(late));
        assertThat(job(t2(late)).orElseThrow().status()).as("T2 ran and finished").isEqualTo("DONE");
        VenueAnswerDb.BookingRow timedOut = booking(late);
        assertThat(timedOut.status()).isEqualTo("CANCELLED");
        assertThat(timedOut.terminalReason()).isEqualTo("CUSTOMER_RECONFIRM_TIMEOUT");
        assertThat(attempt(late).orElseThrow().response()).isEqualTo("TIMED_OUT");
        assertThat(events(IntegrationEvents.LATE_CONFIRM_TIMED_OUT, late)).hasSize(1);
        assertThat(OnlineBookingWorld.dbNow()).as("still before its start: only the status refuses it")
                .isBefore(OWNER.sql("SELECT start_at FROM booking.booking WHERE id = :id").param("id", late)
                        .query(Timestamp.class).single().toInstant());
        Committed before = committed(late);
        assertThat(before.refunds()).as("T2's own decision").hasSize(1);
        long outboxBefore = outboxRows(List.of(late));

        assertThatThrownBy(() -> runner.run(TestCommands.under(scope(late), context -> {
            port.cancel(context, world.venue.id(), world.account.id(), List.of(late));
            return null;
        }))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CANCELLED ONLINE")
                .hasMessageContaining("is not cancelled by an hours change");

        Committed after = committed(late);
        assertThat(after).as("every committed fact of the timed-out booking").isEqualTo(before);
        assertThat(after.booking().terminalReason()).isEqualTo("CUSTOMER_RECONFIRM_TIMEOUT");
        assertThat(after.history()).filteredOn(h -> "CANCELLED".equals(h.toStatus())).singleElement()
                .satisfies(h -> {
                    assertThat(h.terminalReason()).isEqualTo("CUSTOMER_RECONFIRM_TIMEOUT");
                    assertThat(h.actorType()).isEqualTo(ActorType.SYSTEM.name());
                });
        assertThat(after.history()).extracting(HistoryRow::terminalReason).doesNotContain(REASON);
        assertThat(after.refunds()).as("no second decision").hasSize(1);
        assertThat(after.attempt().response()).isEqualTo("TIMED_OUT");
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, late)).as("no BookingCancelled added").isEmpty();
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, after.attempt().holdId())).as("T2's release only")
                .hasSize(1);
        assertThat(outboxRows(List.of(late))).as("no event of the booking or its reservations").isEqualTo(outboxBefore);
    }

    /** The open attempt is gone (a data defect): refused before any write, and the PENDING booking keeps everything. */
    @Test
    void anAwaitingBookingWithoutAnOpenAttemptFailsTheWholeMixedCommand() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking valid = pending(world, world.start());
        UUID late = awaiting(world, world.anotherCustomer(), world.start().plus(Duration.ofHours(2)));
        UUID hold = attempt(late).orElseThrow().holdId();
        LateConfirmDb.attemptLost(late);

        assertRefusedAndNothingChanged(world, valid, late, "AWAITING booking has no open Late Confirmation");

        assertThat(booking(late).status()).isEqualTo(AWAITING);
        assertThat(reservations(late)).filteredOn(r -> r.id().equals(hold)).singleElement()
                .satisfies(reservation -> assertThat(reservation.active()).isTrue());
        assertThat(job(t2(late)).orElseThrow().status()).isEqualTo("PENDING");
        assertThat(count("loyalty.booking_fee_refund WHERE booking_id = :id", late)).isZero();
    }

    /** The hold is no longer active (a data defect): refused before any write, and nothing of either is kept. */
    @Test
    void anAwaitingBookingWithoutItsActiveHoldFailsTheWholeMixedCommand() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking valid = pending(world, world.start());
        UUID late = awaiting(world, world.anotherCustomer(), world.start().plus(Duration.ofHours(2)));
        Attempt open = attempt(late).orElseThrow();
        LateConfirmDb.holdLost(late);

        assertRefusedAndNothingChanged(world, valid, late,
                "AWAITING_CUSTOMER_CONFIRMATION booking holds no active reservation");

        assertThat(booking(late).status()).isEqualTo(AWAITING);
        assertThat(attempt(late).orElseThrow().closedAt()).isNull();
        assertThat(attempt(late).orElseThrow().expiresAt()).isEqualTo(open.expiresAt());
        assertThat(job(t2(late)).orElseThrow().status()).isEqualTo("PENDING");
        assertThat(job(t3(late)).orElseThrow().status()).isEqualTo("PENDING");
        assertThat(count("loyalty.booking_fee_refund WHERE booking_id = :id", late)).isZero();
    }

    /**
     * The lock set without the Customers: every booking is written - the AWAITING one's attempt closed and hold
     * released included - then the Loyalty batch is refused by the database, and the whole command rolls back.
     */
    @Test
    void aMixedBatchWithoutItsCustomersLockedRollsBackTheLateConfirmClosureToo() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking pending = pending(world, world.start());
        UUID late = awaiting(world, world.anotherCustomer(), world.start().plus(Duration.ofHours(2)));
        UUID manual = manual(world, world.start().plus(Duration.ofHours(4)));
        Attempt open = attempt(late).orElseThrow();
        List<UUID> batch = List.of(pending.bookingId(), late, manual);
        long outboxBefore = outboxRows(batch);
        CommandScope withoutCustomers = CommandScope.shared(LockSet.builder().venues(world.venue.id())
                .resources(world.room.id()).build());

        assertThatThrownBy(() -> runner.run(TestCommands.under(withoutCustomers, context -> {
            port.cancel(context, world.venue.id(), world.account.id(), batch);
            return null;
        }))).hasStackTraceContaining("All Loyalty Users must be prelocked in the shared scope");

        assertUnchanged(pending);
        assertAwaitingUnchanged(late, open);
        assertThat(booking(manual).status()).isEqualTo("CONFIRMED");
        assertThat(activeReservation(manual)).isTrue();
        assertThat(job(bookingStart(manual)).orElseThrow().status()).isEqualTo("PENDING");
        assertThat(outboxRows(batch)).as("no event of any booking or its reservations").isEqualTo(outboxBefore);
    }

    // ---------------------------------------------------------------- helpers

    /** Every committed fact of one Online booking the hours change could touch, read on another connection. */
    private record Committed(VenueAnswerDb.BookingRow booking, List<HistoryRow> history, Attempt attempt,
                             Instant warningSentAt, List<LateConfirmDb.Reservation> reservations, String t2,
                             String t3, List<String> refunds, List<String> outboxTypes) {
    }

    private static Committed committed(UUID bookingId) {
        return new Committed(booking(bookingId), history(bookingId), attempt(bookingId).orElseThrow(),
                LateConfirmDb.warningSentAt(bookingId), reservations(bookingId),
                job(t2(bookingId)).orElseThrow().status(), job(t3(bookingId)).orElseThrow().status(),
                OWNER.sql("""
                                SELECT id::text || ' ' || booking_history_id::text || ' ' || treatment
                                  FROM loyalty.booking_fee_refund WHERE booking_id = :id ORDER BY id""")
                        .param("id", bookingId).query(String.class).list(),
                outboxTypes(bookingId));
    }

    /** A real AWAITING booking of {@code customerId}: made, expired by T1, then Late Confirmed by the venue. */
    private UUID awaiting(OnlineBookingWorld world, UUID customerId, Instant start) {
        UUID late = new LateConfirmDb(runner, creation, store, answers)
                .expired(world, customerId, world.room, start, 60);
        ok(lateConfirmations.initiate(world.account.id(), late));
        return late;
    }

    /**
     * The attempt closed at {@code at} without a Customer response, its deadline as it was; its hold released with the
     * reason, with exactly one ReservationReleased.
     */
    private static void assertLateConfirmationClosed(UUID bookingId, Attempt open, Instant at) {
        Attempt closed = attempt(bookingId).orElseThrow();
        assertThat(closed.id()).isEqualTo(open.id());
        assertThat(closed.closedAt()).isEqualTo(at);
        assertThat(closed.response()).as("no Customer response is invented").isNull();
        assertThat(closed.respondedAt()).isNull();
        assertThat(closed.expiresAt()).as("the deadline stays as recorded").isEqualTo(open.expiresAt());
        assertThat(LateConfirmDb.attempts(bookingId)).isEqualTo(1);
        assertThat(reservations(bookingId)).filteredOn(r -> r.id().equals(open.holdId())).singleElement()
                .satisfies(hold -> {
                    assertThat(hold.type()).isEqualTo("LATE_CONFIRM_HOLD");
                    assertThat(hold.active()).isFalse();
                    assertThat(hold.releasedAt()).isEqualTo(at);
                    assertThat(hold.releaseReason()).isEqualTo(REASON);
                });
        assertThat(reservations(bookingId)).filteredOn(r -> r.active()).as("nothing of it is still held").isEmpty();
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, open.holdId())).hasSize(1);
    }

    /** An AWAITING booking as it was: open attempt, active hold, T2/T3 waiting, no refund and no cancellation. */
    private static void assertAwaitingUnchanged(UUID bookingId, Attempt open) {
        assertThat(booking(bookingId).status()).isEqualTo(AWAITING);
        assertThat(booking(bookingId).terminalReason()).isNull();
        assertThat(history(bookingId)).extracting(HistoryRow::toStatus).doesNotContain("CANCELLED");
        Attempt now = attempt(bookingId).orElseThrow();
        assertThat(now.closedAt()).isNull();
        assertThat(now.expiresAt()).isEqualTo(open.expiresAt());
        assertThat(reservations(bookingId)).filteredOn(r -> r.id().equals(open.holdId())).singleElement()
                .satisfies(hold -> assertThat(hold.active()).isTrue());
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, open.holdId())).isEmpty();
        assertThat(job(t2(bookingId)).orElseThrow().status()).isEqualTo("PENDING");
        assertThat(job(t3(bookingId)).orElseThrow().status()).isEqualTo("PENDING");
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, bookingId)).isEmpty();
        assertThat(count("loyalty.booking_fee_refund WHERE booking_id = :id", bookingId)).isZero();
    }

    /** {@link #assertRefunded}, and the decision rests on the booking's own cancellation history row. */
    private static void assertRefundedOnTheCancellation(UUID bookingId, UUID customerId) {
        assertRefunded(bookingId, customerId);
        List<HistoryRow> rows = history(bookingId);
        HistoryRow last = rows.get(rows.size() - 1);
        assertThat(OWNER.sql("""
                        SELECT h.sequence FROM loyalty.booking_fee_refund r
                          JOIN booking.booking_history h ON h.id = r.booking_history_id
                         WHERE r.booking_id = :id AND h.booking_id = :id""")
                .param("id", bookingId).query(Long.class).single())
                .as("the terminal transition this command wrote").isEqualTo(last.sequence());
    }

    /** The database's own final-state assertion of the attempt, called again on the committed rows. */
    private static void assertLateConfirmIntegrityHolds(UUID bookingId) {
        assertThat(OWNER.sql("SELECT 1 FROM (SELECT booking.assert_late_confirmation_row(:id)) checked")
                .param("id", attempt(bookingId).orElseThrow().id()).query(Integer.class).single()).isEqualTo(1);
    }

    /** Makes the job due now and lets the real Late Confirm worker fire it once. */
    private void fireNow(UUID bookingId, String key) {
        DueJobs due = DueJobs.of(context, new LateConfirmExpiryHandler(lateConfirmations),
                new LateConfirmWarningHandler(lateConfirmations));
        runner.run(TestCommands.under(lateConfirmScope(bookingId), command -> {
            due.makeDue(command, key, command.commandNow());
            return null;
        }));
        assertThat(due.fire(key)).as("%s fired", key).isTrue();
    }

    /** The handler's decision, in one command under the booking's locks - as the lease holder's fire would run it. */
    private JobOutcome fireDirectly(UUID bookingId, String type) {
        ScheduledJob job = new ScheduledJob(UUID.randomUUID(), type, "BOOKING", bookingId, Instant.EPOCH, 1,
                type + ":" + bookingId, CorrelationId.generate());
        return runner.run(TestCommands.under(lateConfirmScope(bookingId), command ->
                type.equals("LATE_CONFIRM_EXPIRY") ? lateConfirmations.expire(command, job)
                        : lateConfirmations.warn(command, job)));
    }

    private CommandScope lateConfirmScope(UUID bookingId) {
        return CommandScope.shared(lateConfirmations.lockSetOf(bookingId));
    }

    private CreatedBooking pending(OnlineBookingWorld world, Instant start) {
        return creation.create(world.request(world.customer.id(), world.room, start, 60));
    }

    private CreatedBooking confirmed(OnlineBookingWorld world, Instant start) {
        CreatedBooking created = pending(world, start);
        assertThat(responses.accept(world.account.id(), created.bookingId())).isInstanceOf(CommandAnswer.Ok.class);
        return created;
    }

    private UUID manual(OnlineBookingWorld world, Instant start) {
        return manualBookings.create(world.account.id(),
                ManualBookingRequest.of(world.room.id(), PlayMode.SINGLE, start, 60, null, null)).bookingId();
    }

    /** The one booking cancelled by the venue's own account, in one committed command under its locks. */
    private Instant cancel(OnlineBookingWorld world, UUID bookingId) {
        return runner.run(TestCommands.under(scope(bookingId), context -> {
            port.cancel(context, world.venue.id(), world.account.id(), List.of(bookingId));
            return context.commandNow();
        }));
    }

    private CommandScope scope(UUID bookingId) {
        return scope(List.of(bookingId));
    }

    /** What the caller must lock: every booking's Customer (when it has one), Venue and Resource, as one lock set. */
    private CommandScope scope(List<UUID> bookingIds) {
        List<UUID> users = new ArrayList<>();
        List<UUID> venues = new ArrayList<>();
        List<UUID> resources = new ArrayList<>();
        for (UUID id : bookingIds) {
            runner.run(new FindBooking(store, id)).ifPresent(keys -> {
                venues.add(keys.venueId());
                resources.add(keys.resourceId());
                if (keys.customerId() != null) {
                    users.add(keys.customerId());
                }
            });
        }
        return CommandScope.shared(LockSet.builder().users(users).venues(venues).resources(resources).build());
    }

    private static List<UUID> concat(List<UUID> ids, UUID more) {
        List<UUID> all = new ArrayList<>(ids);
        all.add(more);
        return all;
    }

    /**
     * {@code [valid, invalid]} under the locks of both: refused before any write with {@link IllegalStateException},
     * and the valid booking keeps everything - nothing is skipped and nothing half-done is committed.
     */
    private void assertRefusedAndNothingChanged(OnlineBookingWorld world, CreatedBooking valid, UUID invalid,
                                                String detail) {
        List<UUID> batch = List.of(valid.bookingId(), invalid);
        CommandScope scope = scope(batch);
        long outboxBefore = outboxRows(batch);

        assertThatThrownBy(() -> runner.run(TestCommands.under(scope, context -> {
            port.cancel(context, world.venue.id(), world.account.id(), batch);
            return null;
        }))).isInstanceOf(IllegalStateException.class).hasMessageContaining(detail);

        assertUnchanged(valid);
        assertThat(outboxRows(batch)).as("no event of either booking or their reservations").isEqualTo(outboxBefore);
    }

    /** A PENDING booking as it was made: still PENDING, its reservation, its T1, no refund and no cancellation. */
    private void assertUnchanged(CreatedBooking pending) {
        assertThat(booking(pending.bookingId()).status()).isEqualTo("PENDING");
        assertThat(booking(pending.bookingId()).terminalReason()).isNull();
        assertThat(history(pending.bookingId())).extracting(HistoryRow::toStatus).containsExactly("PENDING");
        assertThat(activeReservation(pending.bookingId())).isTrue();
        assertThat(job(t1(pending.bookingId())).orElseThrow().status()).isEqualTo("PENDING");
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, pending.bookingId())).isEmpty();
        assertThat(count("loyalty.booking_fee_refund WHERE booking_id = :id", pending.bookingId())).isZero();
    }

    /** D1: the last history row is the cancellation, by the venue's account, at the command instant. */
    private static void assertCancelledBy(OnlineBookingWorld world, UUID bookingId, BookingStatus from, Instant at) {
        List<HistoryRow> rows = history(bookingId);
        HistoryRow last = rows.get(rows.size() - 1);
        assertThat(last.fromStatus()).isEqualTo(from.name());
        assertThat(last.toStatus()).isEqualTo("CANCELLED");
        assertThat(last.terminalReason()).isEqualTo(REASON);
        assertThat(last.occurredAt()).isEqualTo(at);
        assertThat(last.actorType()).isEqualTo(ActorType.VENUE.name());
        assertThat(last.actorId()).isEqualTo(world.account.id());
        assertThat(rows).filteredOn(row -> "CANCELLED".equals(row.toStatus())).hasSize(1);
    }

    /** The booking's BOOKING reservation released at {@code at} with the reason, and its ReservationReleased once. */
    private static void assertReleased(UUID bookingId, Instant at) {
        Map<String, Object> row = OWNER.sql("""
                        SELECT id, active_flag, actual_reservation_release_at, release_reason
                          FROM inventory.resource_reservation
                         WHERE booking_id = :id AND reservation_type = 'BOOKING'""")
                .param("id", bookingId).query().singleRow();
        assertThat(row.get("active_flag")).isEqualTo(false);
        assertThat(((Timestamp) row.get("actual_reservation_release_at")).toInstant()).isEqualTo(at);
        assertThat(row.get("release_reason")).isEqualTo(REASON);
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, (UUID) row.get("id"))).hasSize(1);
    }

    /** One FULL_REFUND decision for the Customer, based on the booking's cancellation history row. */
    private static void assertRefunded(UUID bookingId, UUID customerId) {
        Map<String, Object> refund = OWNER.sql("""
                        SELECT r.treatment, r.terminal_reason, r.user_id, h.to_status, h.actor_type
                          FROM loyalty.booking_fee_refund r
                          JOIN booking.booking_history h ON h.id = r.booking_history_id
                         WHERE r.booking_id = :id""")
                .param("id", bookingId).query().singleRow();
        assertThat(refund.get("treatment")).isEqualTo("FULL_REFUND");
        assertThat(refund.get("terminal_reason")).isEqualTo(REASON);
        assertThat(refund.get("user_id")).isEqualTo(customerId);
        assertThat(refund.get("to_status")).isEqualTo("CANCELLED");
        assertThat(refund.get("actor_type")).isEqualTo("VENUE");
    }

    /** The booking's own events: only one BookingCancelled was added - no other synthetic or hours-change event. */
    private static void assertOnlyBookingCancelledAdded(UUID bookingId, List<String> before) {
        List<String> after = outboxTypes(bookingId);
        assertThat(after.subList(0, before.size())).isEqualTo(before);
        assertThat(after.subList(before.size(), after.size())).containsExactly(IntegrationEvents.BOOKING_CANCELLED);
    }

    private JsonNode onlyCancelledEvent(UUID bookingId) {
        List<String> payloads = events(IntegrationEvents.BOOKING_CANCELLED, bookingId);
        assertThat(payloads).hasSize(1);
        try {
            return json.readTree(payloads.get(0));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean activeReservation(UUID bookingId) {
        return OWNER.sql("""
                        SELECT count(*) FROM inventory.resource_reservation
                         WHERE booking_id = :id AND reservation_type = 'BOOKING' AND active_flag""")
                .param("id", bookingId).query(Long.class).single() == 1L;
    }

    private static UUID operatingSession(UUID bookingId) {
        return OWNER.sql("SELECT operating_session_id FROM booking.booking WHERE id = :id")
                .param("id", bookingId).query(UUID.class).optional().orElse(null);
    }

    /** The outbox rows of these bookings and of their reservations. */
    private static long outboxRows(List<UUID> bookingIds) {
        return OWNER.sql("""
                        SELECT count(*) FROM technical.outbox_event e
                         WHERE e.aggregate_id IN (:ids)
                            OR e.aggregate_id IN (SELECT r.id FROM inventory.resource_reservation r
                                                   WHERE r.booking_id IN (:ids))""")
                .param("ids", bookingIds).query(Long.class).single();
    }

    /** TEST-ONLY: a worker holds the job LEASED right now (the lease columns its lifecycle shape requires). */
    private static void leased(String key) {
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        UPDATE technical.scheduled_job
                           SET status = 'LEASED', leased_at = now(), leased_until = now() + interval '5 minutes',
                               leased_by = 'E7-113 test'
                         WHERE idempotency_key = :key""")
                .param("key", key).update());
    }
}
