package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.beans.factory.annotation.Autowired;
import ps.e7gz.booking.internal.BookingStates.Plant;
import ps.e7gz.booking.internal.VenueAnswerDb.HistoryRow;
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
import ps.e7gz.support.boundary.Deadlines;
import ps.e7gz.support.boundary.TestCommands;
import ps.e7gz.venue.api.HoursChangeBookings;

/**
 * E7-113 Phase 3B - {@link HoursChangeBookings#cancel}: the Booking side of an opening-hours change (FR-VEN-23 step 8,
 * FR-VEN-24, Spec v5.0 section 14.8) on bookings made by the real commands, each cancellation one committed command
 * under the lock set the caller must hold (the venue, the bookings' Resources and Customers), so the deferred integrity
 * triggers - the Booking Fee decision, the transition guard - run at COMMIT. The Venue command that will call it is
 * Phase 4; nothing here goes through it.
 */
class HoursChangeCancellationIntegrationTest extends IntegrationTest {

    private static final String REASON = "VENUE_OPENING_HOURS_CHANGED";

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
    void anAwaitingBookingFailsTheWholeCommandUntilPhase3C() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking valid = pending(world, world.start());
        UUID awaiting = new LateConfirmDb(runner, creation, store, answers)
                .expired(world, world.start().plus(Duration.ofHours(2)), 60);
        ok(lateConfirmations.initiate(world.account.id(), awaiting));
        assertRefusedAndNothingChanged(world, valid, awaiting, "AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(booking(awaiting).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(LateConfirmDb.attempt(awaiting).orElseThrow().closedAt()).isNull();
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

    // ---------------------------------------------------------------- helpers

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
