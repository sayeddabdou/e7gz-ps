package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.bookingStart;
import static ps.e7gz.booking.internal.VenueAnswerDb.events;
import static ps.e7gz.booking.internal.VenueAnswerDb.history;
import static ps.e7gz.booking.internal.VenueAnswerDb.job;
import static ps.e7gz.booking.internal.VenueAnswerDb.reservation;
import static ps.e7gz.booking.internal.VenueAnswerDb.t1;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ps.e7gz.booking.internal.VenueAnswerDb.BookingRow;
import ps.e7gz.booking.internal.VenueAnswerDb.HistoryRow;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.concurrency.ConcurrentRace;
import ps.e7gz.messaging.api.CommandAnswer;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.Deadlines;

/**
 * E7-47 (O2) against the real database: Accept, Reject, Reject After Expiry and T1, each with its booking, history,
 * reservation, scheduled-job and outbox consistency; the deadline boundaries to the exact second (PO decision D5); the
 * paused timer (D6); FR-BOK-16 committed with its refusal (D2, D3).
 *
 * <p>Boundaries are hit exactly by moving {@code venue_response_expires_at} relative to the command's own instant inside
 * the command, before the decision runs (the E7-46 pattern for FR-BOK-02's boundary).
 */
class VenueAnswerIntegrationTest extends IntegrationTest {

    static final String REASON = "Room under maintenance";

    @Autowired
    OnlineBookingCreation creation;

    @Autowired
    VenueResponses responses;

    @Autowired
    VenueAnswers answers;

    @Autowired
    VenueAnswerStore store;

    @Autowired
    CommandRunner runner;

    private final UUID correlationId = CorrelationId.generate();
    private CorrelationId.Scope correlation;

    @BeforeEach
    void openCorrelation() {
        correlation = CorrelationId.open(correlationId);
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    // ---------------------------------------------------------------------------------------------- Accept

    @Test
    void acceptConfirmsKeepsTheReservationSupersedesT1AndSchedulesTheStart() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);

        CommandAnswer<VenueBookingRow> answer = responses.accept(world.account.id(), created.bookingId());

        VenueBookingRow row = ((CommandAnswer.Ok<VenueBookingRow>) answer).value();
        assertThat(row.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(row.terminalReason()).isNull();
        assertThat(row.venueResponseExpiresAt()).as("a deadline only while PENDING").isNull();
        assertThat(row.customerUsername()).isEqualTo(customerColumn(created, "username"));
        assertThat(row.customerPhone()).as("visible while CONFIRMED").isEqualTo(customerColumn(created, "phone_e164"));
        BookingRow stored = booking(created.bookingId());
        assertThat(stored.status()).isEqualTo("CONFIRMED");
        assertThat(stored.counted()).as("still an Online commitment").isTrue();
        assertThat(reservation(created.reservationId()).active()).as("the reservation stays held").isTrue();
        List<HistoryRow> history = history(created.bookingId());
        assertThat(history).hasSize(2);
        assertThat(history.get(1)).satisfies(h -> {
            assertThat(h.sequence()).isEqualTo(2);
            assertThat(h.fromStatus()).isEqualTo("PENDING");
            assertThat(h.toStatus()).isEqualTo("CONFIRMED");
            assertThat(h.terminalReason()).isNull();
            assertThat(h.actorType()).isEqualTo("VENUE");
            assertThat(h.actorId()).isEqualTo(world.account.id());
        });
        assertThat(job(t1(created.bookingId())).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(job(bookingStart(created.bookingId())).orElseThrow())
                .satisfies(start -> {
                    assertThat(start.status()).isEqualTo("PENDING");
                    assertThat(start.dueAt()).isEqualTo(created.startAt());
                });
        assertThat(events(IntegrationEvents.BOOKING_CONFIRMED, created.bookingId())).singleElement()
                .satisfies(payload -> assertThat(payload)
                        .contains("\"bookingId\": \"" + created.bookingId() + "\"")
                        .contains("\"customerId\": \"" + created.customerId() + "\"")
                        .contains("\"venueId\": \"" + world.venue.id() + "\"")
                        .contains("\"resourceId\": \"" + world.room.id() + "\"")
                        .contains("\"startAt\": \"" + created.startAt() + "\"")
                        .contains("\"endAt\": \"" + created.endAt() + "\""));
        // D10: exactly one BookingConfirmed and never a BookingAccepted
        assertThat(VenueAnswerDb.outboxTypes(created.bookingId()))
                .containsExactly(IntegrationEvents.BOOKING_CREATED, IntegrationEvents.BOOKING_CONFIRMED)
                .doesNotContain("BookingAccepted");
    }

    @Test
    void acceptOneSecondBeforeTheDeadlineSucceeds() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);

        CommandAnswer<VenueBookingRow> answer = withDeadline(world, created, Duration.ofSeconds(1),
                context -> answers.accept(context, created.bookingId(), world.venue.id(), world.account.id()));

        assertThat(answer).isInstanceOf(CommandAnswer.Ok.class);
        assertThat(booking(created.bookingId()).status()).isEqualTo("CONFIRMED");
    }

    @Test
    void acceptExactlyAtTheDeadlineIsDeadlinePassedAndExpiryWins() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);

        assertRefused(ApiErrorCode.DEADLINE_PASSED, () -> withDeadline(world, created, Duration.ZERO,
                context -> answers.accept(context, created.bookingId(), world.venue.id(), world.account.id())));
        assertUnchanged(created);

        // ... and T1 at the same instant expires it
        JobOutcome outcome = withDeadline(world, created, Duration.ZERO,
                context -> answers.expire(context, t1Job(created)));
        assertThat(outcome).isInstanceOf(JobOutcome.Done.class);
        assertThat(booking(created.bookingId()).status()).isEqualTo("VENUE_RESPONSE_EXPIRED");
    }

    @Test
    void aSuspendedVenueMayStillAcceptARequestMadeBeforeTheSuspension() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        VenueAnswerDb.marketplaceStatus(world.venue.id(), "SUSPENDED");

        CommandAnswer<VenueBookingRow> answer = responses.accept(world.account.id(), created.bookingId());

        assertThat(answer).isInstanceOf(CommandAnswer.Ok.class);
        assertThat(booking(created.bookingId()).status()).isEqualTo("CONFIRMED");
    }

    @Test
    void anotherVenueCannotActOnTheBooking() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        OnlineBookingWorld other = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);

        assertRefused(ApiErrorCode.NOT_FOUND, () -> responses.accept(other.account.id(), created.bookingId()));
        assertRefused(ApiErrorCode.NOT_FOUND, () -> responses.reject(other.account.id(), created.bookingId(), REASON));
        assertRefused(ApiErrorCode.NOT_FOUND,
                () -> responses.rejectAfterExpiry(other.account.id(), created.bookingId()));
        assertRefused(ApiErrorCode.NOT_FOUND, () -> responses.accept(world.account.id(), UUID.randomUUID()));
        assertUnchanged(created);
    }

    @Test
    void acceptOnABookingNoLongerPendingIsBookingStateChanged() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        customerCancels(world, created);

        assertRefused(ApiErrorCode.BOOKING_STATE_CHANGED,
                () -> responses.accept(world.account.id(), created.bookingId()));
        assertRefused(ApiErrorCode.BOOKING_STATE_CHANGED,
                () -> responses.reject(world.account.id(), created.bookingId(), REASON));
        assertRefused(ApiErrorCode.BOOKING_STATE_CHANGED,
                () -> responses.rejectAfterExpiry(world.account.id(), created.bookingId()));
        assertThat(booking(created.bookingId()).terminalReason()).isEqualTo("CUSTOMER_CANCELLED");
    }

    @Test
    void acceptAfterT1ExpiredTheBookingIsBookingStateChanged() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        withDeadline(world, created, Duration.ZERO, context -> answers.expire(context, t1Job(created)));

        assertRefused(ApiErrorCode.BOOKING_STATE_CHANGED,
                () -> responses.accept(world.account.id(), created.bookingId()));
        assertRefused(ApiErrorCode.BOOKING_STATE_CHANGED,
                () -> responses.reject(world.account.id(), created.bookingId(), REASON));
    }

    @Test
    void aNonOperationalBlockRefusesAcceptWithoutCancelling() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        VenueAnswerDb.resourceDisabled(world.room.id());

        assertRefused(ApiErrorCode.RESOURCE_NOT_ELIGIBLE,
                () -> responses.accept(world.account.id(), created.bookingId()));
        assertUnchanged(created);
    }

    @Test
    void aSellingBlockWithoutAnEmergencyIsNotAnOperationalCauseAndCancelsNothing() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        VenueAnswerDb.venueSellingBlockWithoutEmergency(world.venue.id());

        assertRefused(ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE,
                () -> responses.accept(world.account.id(), created.bookingId()));
        assertUnchanged(created);
    }

    // ------------------------------------------------------------------------------------- FR-BOK-16 (D2, D3)

    @Test
    void aVenueEmergencyCancelsTheBookingUnderItsCauseAndCommitsTheRefusal() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        VenueAnswerDb.venueEmergency(world.venue.id());

        assertOperationallyCancelled(world, created, BookingTerminalReason.VENUE_EMERGENCY_CLOSURE,
                ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE);
    }

    @Test
    void aResourceEmergencyCancelsTheBookingUnderItsCause() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        VenueAnswerDb.resourceEmergency(world.venue.id(), world.room.id());

        assertOperationallyCancelled(world, created, BookingTerminalReason.RESOURCE_EMERGENCY_OUTAGE,
                ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE);
    }

    @Test
    void anOwnerReviewCancelsTheBookingUnderItsCause() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        VenueAnswerDb.ownerReview(world.room.id());

        assertOperationallyCancelled(world, created, BookingTerminalReason.RESOURCE_OWNER_REVIEW_REQUIRED,
                ApiErrorCode.RESOURCE_NOT_ELIGIBLE);
    }

    @Test
    void ownerReviewOutranksAnEmergencyAsFrBok23Orders() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        VenueAnswerDb.venueEmergency(world.venue.id());
        VenueAnswerDb.ownerReview(world.room.id());

        assertOperationallyCancelled(world, created, BookingTerminalReason.RESOURCE_OWNER_REVIEW_REQUIRED,
                ApiErrorCode.RESOURCE_NOT_ELIGIBLE);
    }

    // ------------------------------------------------------------------------------------------ paused (D6)

    @Test
    void aPausedTimerRefusesAcceptAndChangesNothing() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        VenueAnswerDb.venueEmergency(world.venue.id());
        VenueAnswerDb.paused(created.bookingId());

        assertRefused(ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE,
                () -> responses.accept(world.account.id(), created.bookingId()));
        assertUnchanged(created);
        assertStillPaused(created);
    }

    /**
     * PO decision 10 Oct 2026 (E7-47 review): FR-OPS-34 disables only Accept, so the venue may still reject while the
     * timer is paused - even past the stored deadline, which a paused timer does not run against (D6). The pause ends
     * with the rejection: counted into the booking's cumulative pause (FR-OPS-37) and its history row closed once.
     */
    @Test
    void aPausedTimerStillLetsTheVenueRejectAndEndsThePause() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        VenueAnswerDb.venueEmergency(world.venue.id());
        VenueAnswerDb.paused(created.bookingId());

        VenueBookingRow row = withDeadline(world, created, Duration.ofMinutes(-5),
                context -> answers.reject(context, created.bookingId(), world.venue.id(), world.account.id(), REASON));

        assertThat(row.status()).isEqualTo(BookingStatus.REJECTED);
        assertThat(row.terminalReason()).isEqualTo(BookingTerminalReason.VENUE_REJECTED);
        assertThat(row.venueResponsePaused()).isFalse();
        assertThat(VenueAnswerDb.responsePaused(created.bookingId())).isFalse();
        assertThat(VenueAnswerDb.OWNER.sql("""
                        SELECT cumulative_emergency_pause_duration IS NOT NULL
                               AND cumulative_emergency_pause_duration >= interval '0'
                          FROM booking.booking WHERE id = :id""")
                .param("id", created.bookingId()).query(Boolean.class).single()).isTrue();
        assertThat(VenueAnswerDb.OWNER.sql("""
                        SELECT count(*) FROM operations.booking_timer_pause_history
                         WHERE booking_id = :id AND pause_kind = 'VENUE_RESPONSE' AND pause_ended_at IS NULL""")
                .param("id", created.bookingId()).query(Integer.class).single()).as("no open pause").isZero();
        assertThat(reservation(created.reservationId()).active()).isFalse();
        assertThat(job(t1(created.bookingId())).orElseThrow().status()).as("the PAUSED T1 is withdrawn")
                .isEqualTo("SUPERSEDED");
        assertThat(events(IntegrationEvents.BOOKING_REJECTED, created.bookingId())).hasSize(1);
    }

    /** D7 (b): the pause is checked before the FR-BOK-16 cancel, also for a resource-level emergency. */
    @Test
    void aPausedTimerUnderAResourceEmergencyIsRefusedNotCancelled() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        VenueAnswerDb.resourceEmergency(world.venue.id(), world.room.id());
        VenueAnswerDb.paused(created.bookingId());

        assertRefused(ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE,
                () -> responses.accept(world.account.id(), created.bookingId()));
        assertUnchanged(created);
        assertStillPaused(created);
    }

    /** D8: a pause still waiting after its emergency ended (FR-OPS-39) is the venue's own state. */
    @Test
    void aPausedTimerWhoseEmergencyEndedIsRefusedAsVenueLevel() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        VenueAnswerDb.resourceEmergency(world.venue.id(), world.room.id());
        VenueAnswerDb.paused(created.bookingId());
        VenueAnswerDb.endEmergencies(world.venue.id());

        assertRefused(ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE,
                () -> responses.accept(world.account.id(), created.bookingId()));
        assertUnchanged(created);
        assertStillPaused(created);
    }

    /** D9: banned after the request, the ban cascade not run yet - refused with the neutral code, nothing changes. */
    @Test
    void aCustomerBannedAfterTheRequestIsCustomerNotEligibleAndTheBookingStaysPending() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        ps.e7gz.trust.BanFixtures.activeBan(created.customerId());

        assertRefused(ApiErrorCode.CUSTOMER_NOT_ELIGIBLE,
                () -> responses.accept(world.account.id(), created.bookingId()));
        assertUnchanged(created);
        assertThat(job(t1(created.bookingId())).orElseThrow().status()).isEqualTo("PENDING");
    }

    @Test
    void aPausedTimerIsNeverExpiredByTheClockAlone() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        VenueAnswerDb.resourceEmergency(world.venue.id(), world.room.id());
        VenueAnswerDb.paused(created.bookingId());

        // long past the stored deadline: Accept is still refused as paused, never DEADLINE_PASSED
        assertRefused(ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE, () -> withDeadline(world, created,
                Duration.ofMinutes(-30),
                context -> answers.accept(context, created.bookingId(), world.venue.id(), world.account.id())));
        assertThatThrownBy(() -> withDeadline(world, created, Duration.ofMinutes(-30),
                context -> answers.expire(context, t1Job(created))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("paused");
        assertUnchanged(created);
    }

    // ------------------------------------------------------------------------------------------------ Reject

    @Test
    void rejectReleasesTheReservationAtOnceAndKeepsTheReasonTextApartFromTheCode() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);

        VenueBookingRow row = responses.reject(world.account.id(), created.bookingId(), REASON);

        assertThat(row.status()).isEqualTo(BookingStatus.REJECTED);
        assertThat(row.terminalReason()).isEqualTo(BookingTerminalReason.VENUE_REJECTED);
        assertThat(row.rejectReasonText()).isEqualTo(REASON);
        BookingRow stored = booking(created.bookingId());
        assertThat(stored.terminalAt()).isNotNull();
        assertThat(stored.feeTreatment()).isEqualTo("FULL_REFUND");
        assertThat(stored.rejectText()).isEqualTo(REASON);
        assertThat(stored.counted()).as("the commitment slot is freed").isFalse();
        VenueAnswerDb.ReservationRow released = reservation(created.reservationId());
        assertThat(released.active()).isFalse();
        assertThat(released.releasedAt()).isEqualTo(stored.terminalAt());
        assertThat(released.releaseReason()).isEqualTo("VENUE_REJECTED");
        assertThat(history(created.bookingId()).get(1)).satisfies(h -> {
            assertThat(h.toStatus()).isEqualTo("REJECTED");
            assertThat(h.terminalReason()).isEqualTo("VENUE_REJECTED");
            assertThat(h.actorType()).isEqualTo("VENUE");
        });
        assertThat(job(t1(created.bookingId())).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(job(bookingStart(created.bookingId()))).isEmpty();
        assertThat(events(IntegrationEvents.BOOKING_REJECTED, created.bookingId())).singleElement()
                .satisfies(payload -> assertThat(payload).contains("\"terminalReason\": \"VENUE_REJECTED\"")
                        .doesNotContain(REASON));
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, created.reservationId())).hasSize(1);
    }

    @Test
    void rejectOneSecondBeforeTheDeadlineSucceedsAndAtItIsDeadlinePassed() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking late = pending(world);
        CreatedBooking inTime = pending(world, world.start().plus(Duration.ofHours(3)));

        assertRefused(ApiErrorCode.DEADLINE_PASSED, () -> withDeadline(world, late, Duration.ZERO,
                context -> answers.reject(context, late.bookingId(), world.venue.id(), world.account.id(), REASON)));
        assertUnchanged(late);
        VenueBookingRow row = withDeadline(world, inTime, Duration.ofSeconds(1),
                context -> answers.reject(context, inTime.bookingId(), world.venue.id(), world.account.id(), REASON));
        assertThat(row.status()).isEqualTo(BookingStatus.REJECTED);
    }

    // ------------------------------------------------------------------------------------------- T1 and RAE

    @Test
    void t1ExpiresAtTheStoredDeadlineNonTerminallyAndReleasesTheReservation() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);

        // fired late (the deadline is moved back to the creation instant, the earliest a reservation allows): the
        // outcome and its dates are those of the stored deadline, not of when the worker ran
        Instant[] deadline = {created.createdAt()};
        JobOutcome outcome = runner.run(command(world, created, context -> {
            moveDeadline(context, created, deadline[0]);
            assertThat(context.commandNow()).isAfter(deadline[0]);
            return answers.expire(context, t1Job(created));
        }));

        assertThat(outcome).isInstanceOf(JobOutcome.Done.class);
        BookingRow stored = booking(created.bookingId());
        assertThat(stored.status()).isEqualTo("VENUE_RESPONSE_EXPIRED");
        assertThat(stored.terminalReason()).as("non-terminal: no reason").isNull();
        assertThat(stored.terminalAt()).isNull();
        assertThat(stored.feeTreatment()).isNull();
        assertThat(stored.counted()).as("the commitment slot is freed").isFalse();
        VenueAnswerDb.ReservationRow released = reservation(created.reservationId());
        assertThat(released.active()).isFalse();
        assertThat(released.releasedAt()).isEqualTo(deadline[0]);
        assertThat(released.releaseReason()).isEqualTo("VENUE_RESPONSE_EXPIRED");
        assertThat(history(created.bookingId()).get(1)).satisfies(h -> {
            assertThat(h.occurredAt()).isEqualTo(deadline[0]);
            assertThat(h.toStatus()).isEqualTo("VENUE_RESPONSE_EXPIRED");
            assertThat(h.terminalReason()).isNull();
            assertThat(h.actorType()).isEqualTo("SYSTEM");
        });
        assertThat(events(IntegrationEvents.VENUE_RESPONSE_EXPIRED, created.bookingId())).singleElement()
                .satisfies(payload -> assertThat(payload).contains("\"venueResponseExpiresAt\": \"" + deadline[0]));
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, created.reservationId())).hasSize(1);
    }

    @Test
    void t1BeforeTheStoredDeadlineIsRescheduledToItAndChangesNothing() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        Instant[] deadline = new Instant[1];

        JobOutcome outcome = withDeadline(world, created, Duration.ofSeconds(1), context -> {
            deadline[0] = context.commandNow().plusSeconds(1);
            return answers.expire(context, t1Job(created));
        });

        assertThat(outcome).isEqualTo(JobOutcome.rescheduleAt(deadline[0]));
        assertThat(booking(created.bookingId()).status()).isEqualTo("PENDING");
        assertThat(reservation(created.reservationId()).active()).isTrue();
    }

    @Test
    void theT1HandlerFiresUnderTheBookingsLocks() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        VenueResponseExpiryHandler handler = new VenueResponseExpiryHandler(runner, store, answers);

        assertThat(handler.type()).isEqualTo("VENUE_RESPONSE_EXPIRY");
        assertThat(handler.lockSet(t1Job(created))).satisfies(locks -> {
            assertThat(locks.users()).containsExactly(created.customerId());
            assertThat(locks.venues()).containsExactly(world.venue.id());
            assertThat(locks.resources()).containsExactly(world.room.id());
        });
        assertThat(handler.lockSet(new ScheduledJob(UUID.randomUUID(), "VENUE_RESPONSE_EXPIRY", "BOOKING",
                UUID.randomUUID(), Instant.now(), 1, "VENUE_RESPONSE_EXPIRY:x", UUID.randomUUID())).users())
                .as("an unknown booking locks nothing and is superseded").isEmpty();
        // before the deadline the handler only reschedules: nothing changes
        JobOutcome outcome = runner.run(command(world, created, context -> handler.fire(context, t1Job(created))));
        assertThat(outcome).isEqualTo(JobOutcome.rescheduleAt(created.venueResponseExpiresAt()));
        assertThat(booking(created.bookingId()).status()).isEqualTo("PENDING");
    }

    @Test
    void t1OnABookingAlreadyAnsweredIsSuperseded() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        responses.reject(world.account.id(), created.bookingId(), REASON);

        JobOutcome outcome = runner.run(command(world, created, context -> answers.expire(context, t1Job(created))));

        assertThat(outcome).isInstanceOf(JobOutcome.Superseded.class);
        assertThat(booking(created.bookingId()).status()).isEqualTo("REJECTED");
    }


    @Test
    void rejectAfterExpiryRejectsTheMissedRequestBeforeItsStart() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        assertRefused(ApiErrorCode.BOOKING_STATE_CHANGED,
                () -> responses.rejectAfterExpiry(world.account.id(), created.bookingId()));
        withDeadline(world, created, Duration.ZERO, context -> answers.expire(context, t1Job(created)));

        VenueBookingRow row = responses.rejectAfterExpiry(world.account.id(), created.bookingId());

        assertThat(row.status()).isEqualTo(BookingStatus.REJECTED);
        assertThat(row.terminalReason()).isEqualTo(BookingTerminalReason.REJECTED_AFTER_EXPIRY);
        assertThat(row.rejectReasonText()).isNull();
        BookingRow stored = booking(created.bookingId());
        assertThat(stored.feeTreatment()).isEqualTo("FULL_REFUND");
        assertThat(stored.terminalAt()).isNotNull();
        assertThat(history(created.bookingId())).extracting(HistoryRow::toStatus)
                .containsExactly("PENDING", "VENUE_RESPONSE_EXPIRED", "REJECTED");
        assertThat(history(created.bookingId()).get(2).fromStatus()).isEqualTo("VENUE_RESPONSE_EXPIRED");
        assertThat(events(IntegrationEvents.BOOKING_REJECTED, created.bookingId())).singleElement()
                .satisfies(payload -> assertThat(payload).contains("\"terminalReason\": \"REJECTED_AFTER_EXPIRY\""));
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, created.reservationId()))
                .as("released once, by the expiry; nothing left to release").hasSize(1);
        assertRefused(ApiErrorCode.BOOKING_STATE_CHANGED,
                () -> responses.rejectAfterExpiry(world.account.id(), created.bookingId()));
    }

    @Test
    void theMissedListShowsExpiredRequestsBeforeTheirStartAndThePendingListThePendingOnes() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking expired = pending(world);
        CreatedBooking waiting = pending(world, world.start().plus(Duration.ofHours(3)));
        withDeadline(world, expired, Duration.ZERO, context -> answers.expire(context, t1Job(expired)));

        assertThat(bookings.pending(world.account.id())).extracting(VenueBookingRow::bookingId)
                .containsExactly(waiting.bookingId());
        assertThat(bookings.pending(world.account.id()).get(0).venueResponseExpiresAt())
                .isEqualTo(waiting.venueResponseExpiresAt());
        assertThat(bookings.missed(world.account.id())).extracting(VenueBookingRow::bookingId)
                .containsExactly(expired.bookingId());
        assertThat(bookings.missed(OnlineBookingWorld.create().account.id())).isEmpty();
    }

    // ----------------------------------------------------------------------------------- races and rollback

    /**
     * Serialised by {@code begin_command}. Cancel first: the Accept finds no PENDING booking. Accept first: the booking
     * is CONFIRMED before its start, which the customer may still cancel (PO decision 11 Oct 2026, E7-49, D2), so both
     * succeed, in that order. Either way the booking ends CANCELLED with its room released.
     */
    @Test
    void customerCancellationAndVenueAcceptRacingLeaveOneConsistentOutcome() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);

        ConcurrentRace.Result<String> race = ConcurrentRace.run(2, Duration.ofSeconds(30), (racer, start) -> {
            try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
                Command<String> racing = command(world, created, context -> {
                    if (racer == 0) {
                        answers.accept(context, created.bookingId(), world.venue.id(), world.account.id());
                        return "ACCEPTED";
                    }
                    cancelAsCustomer(context, created);
                    return "CANCELLED";
                });
                // both race into begin_command, which serialises them on the booking's Customer, Venue and Resource
                start.await();
                return runner.run(racing);
            }
        });

        BookingRow stored = booking(created.bookingId());
        assertThat(stored.status()).isEqualTo("CANCELLED");
        assertThat(stored.terminalReason()).isEqualTo("CUSTOMER_CANCELLED");
        assertThat(reservation(created.reservationId()).active()).isFalse();
        if (race.values().size() == 2) {
            assertThat(race.values()).containsExactlyInAnyOrder("ACCEPTED", "CANCELLED");
            assertThat(history(created.bookingId())).extracting(HistoryRow::toStatus)
                    .containsExactly("PENDING", "CONFIRMED", "CANCELLED");
            assertThat(job(bookingStart(created.bookingId())).orElseThrow().status()).isEqualTo("SUPERSEDED");
        } else {
            assertThat(race.values()).containsExactly("CANCELLED");
            assertThat(race.failures()).singleElement().isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.code()).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED));
            assertThat(history(created.bookingId())).hasSize(2);
            assertThat(job(bookingStart(created.bookingId()))).isEmpty();
        }
    }

    @Test
    void customerCancellationAndVenueRejectRacingLeaveExactlyOneOutcome() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);

        ConcurrentRace.Result<String> race = ConcurrentRace.run(2, Duration.ofSeconds(30), (racer, start) -> {
            try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
                Command<String> racing = command(world, created, context -> {
                    if (racer == 0) {
                        answers.reject(context, created.bookingId(), world.venue.id(), world.account.id(), REASON);
                        return "REJECTED";
                    }
                    cancelAsCustomer(context, created);
                    return "CANCELLED";
                });
                // both race into begin_command, which serialises them on the booking's Customer, Venue and Resource
                start.await();
                return runner.run(racing);
            }
        });

        assertThat(race.values()).hasSize(1);
        assertThat(race.failures()).singleElement().isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED));
        assertThat(booking(created.bookingId()).status()).isEqualTo(race.values().get(0));
        assertThat(reservation(created.reservationId()).active()).isFalse();
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, created.reservationId())).hasSize(1);
        assertThat(history(created.bookingId())).hasSize(2);
    }

    @Test
    void aFailureAfterTheAcceptRollsBackTheTransitionHistoryJobsAndEvents() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);

        assertThatThrownBy(() -> runner.run(command(world, created, context -> {
            answers.accept(context, created.bookingId(), world.venue.id(), world.account.id());
            throw new IllegalStateException("fail after every write");
        }))).hasMessage("fail after every write");

        assertUnchanged(created);
        assertThat(job(t1(created.bookingId())).orElseThrow().status()).isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    @Autowired
    VenueBookings bookings;

    @Autowired
    CustomerCancellation cancellation;

    private static void moveDeadline(CommandContext context, CreatedBooking created, Instant deadline) {
        Deadlines.move(context, "booking.booking.venue_response_expires_at", created.bookingId(), deadline);
    }

    private static String customerColumn(CreatedBooking created, String column) {
        return OnlineBookingWorld.OWNER.sql("SELECT " + column + " FROM iam.user_account WHERE id = :id")
                .param("id", created.customerId()).query(String.class).single();
    }

    private CreatedBooking pending(OnlineBookingWorld world) {
        return pending(world, world.start());
    }

    /** A PENDING booking of a new customer each time, so two bookings of one world never overlap a commitment. */
    private CreatedBooking pending(OnlineBookingWorld world, Instant start) {
        return creation.create(world.request(world.anotherCustomer(), world.room, start, 60));
    }

    private void assertOperationallyCancelled(OnlineBookingWorld world, CreatedBooking created,
                                              BookingTerminalReason reason, ApiErrorCode code) {
        CommandAnswer<VenueBookingRow> answer = responses.accept(world.account.id(), created.bookingId());

        assertThat(answer).isInstanceOfSatisfying(CommandAnswer.Refused.class,
                refused -> assertThat(refused.refusal().code()).isEqualTo(code));
        BookingRow stored = booking(created.bookingId());
        assertThat(stored.status()).as("committed although the venue gets a 422").isEqualTo("CANCELLED");
        assertThat(stored.terminalReason()).isEqualTo(reason.name());
        assertThat(stored.feeTreatment()).isEqualTo("FULL_REFUND");
        assertThat(stored.counted()).isFalse();
        assertThat(reservation(created.reservationId()).active()).isFalse();
        assertThat(reservation(created.reservationId()).releaseReason()).isEqualTo(reason.name());
        assertThat(history(created.bookingId()).get(1)).satisfies(h -> {
            assertThat(h.toStatus()).isEqualTo("CANCELLED");
            assertThat(h.terminalReason()).isEqualTo(reason.name());
            assertThat(h.actorType()).as("no venue blame").isEqualTo("SYSTEM");
        });
        assertThat(job(t1(created.bookingId())).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(job(bookingStart(created.bookingId()))).isEmpty();
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, created.bookingId())).singleElement()
                .satisfies(payload -> assertThat(payload).contains("\"terminalReason\": \"" + reason + "\""));
        assertThat(events(IntegrationEvents.BOOKING_CONFIRMED, created.bookingId())).isEmpty();
    }

    /** Still PENDING with everything creation left: reservation, one history row, T1 waiting, nothing else. */
    private static void assertUnchanged(CreatedBooking created) {
        BookingRow stored = booking(created.bookingId());
        assertThat(stored.status()).isEqualTo("PENDING");
        assertThat(stored.expiresAt()).isEqualTo(created.venueResponseExpiresAt());
        assertThat(reservation(created.reservationId()).active()).isTrue();
        assertThat(history(created.bookingId())).hasSize(1);
        assertThat(job(t1(created.bookingId())).orElseThrow().status()).isIn("PENDING", "PAUSED");
        assertThat(job(bookingStart(created.bookingId()))).isEmpty();
        assertThat(events(IntegrationEvents.BOOKING_CONFIRMED, created.bookingId())).isEmpty();
        assertThat(events(IntegrationEvents.BOOKING_REJECTED, created.bookingId())).isEmpty();
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, created.bookingId())).isEmpty();
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, created.reservationId())).isEmpty();
    }

    /** D7 (a): still paused, T1 still PAUSED, and no outbox row beyond creation's own BookingCreated. */
    private static void assertStillPaused(CreatedBooking created) {
        assertThat(VenueAnswerDb.responsePaused(created.bookingId())).isTrue();
        assertThat(job(t1(created.bookingId())).orElseThrow().status()).isEqualTo("PAUSED");
        assertThat(VenueAnswerDb.outboxTypes(created.bookingId())).containsExactly(IntegrationEvents.BOOKING_CREATED);
    }

    private static void assertRefused(ApiErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    /**
     * Runs {@code body} in a command with the booking's lock set, after moving {@code venue_response_expires_at} to the
     * command's own instant plus {@code offset} - the only way to hit a boundary to the exact second. A refusal rolls
     * the move back with everything else.
     */
    private <R> R withDeadline(OnlineBookingWorld world, CreatedBooking created, Duration offset,
                               Function<CommandContext, R> body) {
        return runner.run(command(world, created, context -> {
            moveDeadline(context, created, context.commandNow().plus(offset));
            return body.apply(context);
        }));
    }

    private <R> Command<R> command(OnlineBookingWorld world, CreatedBooking created,
                                   Function<CommandContext, R> body) {
        CommandScope scope = runner.run(new FindBooking(store, created.bookingId())).orElseThrow().scope();
        return new Command<>() {
            @Override
            public EligibilityCategory eligibilityCategory() {
                return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
            }

            @Override
            public CommandScope scope() {
                return scope;
            }

            @Override
            public R execute(CommandContext context) {
                return body.apply(context);
            }
        };
    }

    /** T1 of the booking as the worker would hand it over. */
    private static ScheduledJob t1Job(CreatedBooking created) {
        return new ScheduledJob(UUID.randomUUID(), CreateOnlineBookingCommand.VENUE_RESPONSE_EXPIRY,
                CreateOnlineBookingCommand.BOOKING_SUBJECT, created.bookingId(), created.venueResponseExpiresAt(), 1,
                t1(created.bookingId()), CorrelationId.generate());
    }

    /** The customer's real cancel (E7-49, FR-BOK-31), under the same locks as the venue's answer it races. */
    private void cancelAsCustomer(CommandContext context, CreatedBooking created) {
        cancellation.cancel(context, created.bookingId(), created.customerId());
    }

    private void customerCancels(OnlineBookingWorld world, CreatedBooking created) {
        runner.run(command(world, created, context -> {
            cancelAsCustomer(context, created);
            return null;
        }));
    }
}
