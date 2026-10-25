package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.booking.internal.BookingFlows.OWNER_PIN;
import static ps.e7gz.booking.internal.BookingStates.minutesFromNow;
import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.bookingStart;
import static ps.e7gz.booking.internal.VenueAnswerDb.events;
import static ps.e7gz.booking.internal.VenueAnswerDb.history;
import static ps.e7gz.booking.internal.VenueAnswerDb.job;
import static ps.e7gz.booking.internal.VenueAnswerDb.reservation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import ps.e7gz.admin.api.AdminAlerts;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.booking.internal.BookingStates.Plant;
import ps.e7gz.booking.internal.VenueAnswerDb.BookingRow;
import ps.e7gz.booking.internal.VenueAnswerDb.HistoryRow;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.inventory.ledger.api.ActiveReservation;
import ps.e7gz.inventory.ledger.api.NewReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.loyalty.api.BookingFeeRefunds;
import ps.e7gz.messaging.api.CommandAnswer;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.venue.api.OwnerPasswordGate;
import ps.e7gz.venue.api.VenueAccountVenues;
import ps.e7gz.venue.api.VenueEligibility;

/**
 * E7-114 - the venue cancels a CONFIRMED Online Booking with the Owner Password, on the real database, through the real
 * protected command and the Owner Password gate (FR-VEN-03, FR-VEN-05, FRS section 2.1, FR-PTS-15): one transaction
 * releases the reservation at the command instant, records CANCELLED / VENUE_CANCELLED_CONFIRMED_BOOKING with fee
 * treatment FULL_REFUND and its fee decision, frees the commitment, appends the venue's history row, withdraws T4,
 * writes one {@code BookingCancelled} and the SUCCESS audit row - and no Trust input, no-show or attendance. Every
 * refusal changes nothing.
 */
class VenueConfirmedCancellationIntegrationTest extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    OnlineBookingCreation creation;

    @Autowired
    VenueResponses responses;

    @Autowired
    VenueConfirmedCancellations cancellations;

    @Autowired
    CommandRunner runner;

    private BookingFlows flows;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void openCorrelation() {
        flows = BookingFlows.of(context);
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    // ---------------------------------------------------------------------------------------------- the cancellation

    @Test
    void aConfirmedOnlineBookingIsCancelledAtomicallyWithEveryEffect() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);

        VenueBookingRow row = cancellations.cancel(world.account.id(), created.bookingId(), OWNER_PIN);

        assertThat(row.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(row.terminalReason()).isEqualTo(BookingTerminalReason.VENUE_CANCELLED_CONFIRMED_BOOKING);
        assertThat(row.source()).isEqualTo(BookingSource.ONLINE);
        BookingRow stored = booking(created.bookingId());
        assertThat(stored.status()).isEqualTo("CANCELLED");
        assertThat(stored.terminalReason()).isEqualTo("VENUE_CANCELLED_CONFIRMED_BOOKING");
        assertThat(stored.feeTreatment()).as("FR-PTS-15, from booking.expected_fee_treatment").isEqualTo("FULL_REFUND");
        assertThat(stored.counted()).as("the commitment slot is free again").isFalse();
        assertThat(reservation(created.reservationId())).satisfies(released -> {
            assertThat(released.active()).isFalse();
            assertThat(released.releasedAt()).as("released at the cancel instant").isEqualTo(stored.terminalAt());
            assertThat(released.releaseReason()).isEqualTo("VENUE_CANCELLED_CONFIRMED_BOOKING");
        });
        List<HistoryRow> history = history(created.bookingId());
        assertThat(history).hasSize(3);
        assertThat(history.get(2)).satisfies(h -> {
            assertThat(h.fromStatus()).isEqualTo("CONFIRMED");
            assertThat(h.toStatus()).isEqualTo("CANCELLED");
            assertThat(h.terminalReason()).isEqualTo("VENUE_CANCELLED_CONFIRMED_BOOKING");
            assertThat(h.actorType()).isEqualTo("VENUE");
            assertThat(h.actorId()).isEqualTo(world.account.id());
            assertThat(h.occurredAt()).isEqualTo(stored.terminalAt());
        });
        assertThat(refunds(created.bookingId())).as("one fee decision, from the database's reason map")
                .containsExactly("FULL_REFUND VENUE_CANCELLED_CONFIRMED_BOOKING " + created.customerId());
        assertThat(job(bookingStart(created.bookingId())).orElseThrow().status()).as("T4 withdrawn")
                .isEqualTo("SUPERSEDED");
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, created.bookingId())).singleElement()
                .satisfies(payload -> assertThat(payload)
                        .contains("\"bookingId\": \"" + created.bookingId() + "\"")
                        .contains("\"customerId\": \"" + created.customerId() + "\"")
                        .contains("\"venueId\": \"" + world.venue.id() + "\"")
                        .contains("\"terminalReason\": \"VENUE_CANCELLED_CONFIRMED_BOOKING\"")
                        .doesNotContain(OWNER_PIN));
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, created.reservationId())).hasSize(1);
        assertThat(VenueAnswerDb.outboxTypes(created.bookingId())).containsExactly(IntegrationEvents.BOOKING_CREATED,
                IntegrationEvents.BOOKING_CONFIRMED, IntegrationEvents.BOOKING_CANCELLED);
        assertThat(notifications(created.bookingId())).as("the customer notification is E7-55's consumer's").isZero();
    }

    @Test
    void theCancellationCarriesNoCustomerPenalty() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);

        cancellations.cancel(world.account.id(), created.bookingId(), OWNER_PIN);

        assertThat(count("trust.trust_event WHERE booking_id = :id", created.bookingId())).as("no Trust input")
                .isZero();
        assertThat(count("trust.trust_event WHERE subject_user_id = :id", created.customerId())).isZero();
        assertThat(count("attendance.attendance_record WHERE booking_id = :id", created.bookingId()))
                .as("no no-show, no attendance write").isZero();
        assertThat(history(created.bookingId())).extracting(HistoryRow::toStatus)
                .as("never NO_SHOW").doesNotContain("NO_SHOW");
    }

    @Test
    void theCancelledSlotAndTheCommitmentAreFreeAtOnce() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);

        cancellations.cancel(world.account.id(), created.bookingId(), OWNER_PIN);

        CreatedBooking again = creation.create(world.request(created.customerId(), world.room, created.startAt(), 60));
        assertThat(booking(again.bookingId()).status()).as("same customer, same room, same interval")
                .isEqualTo("PENDING");
    }

    // --------------------------------------------------------------------------------------------- the Owner Password

    @Test
    void theRightPinIsAuditedAsASuccessOnTheCancellationsOwnRow() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);

        cancellations.cancel(world.account.id(), created.bookingId(), OWNER_PIN);

        assertThat(audit(created.bookingId())).containsExactly("VENUE_CANCELLED_CONFIRMED_BOOKING BOOKING VENUE "
                + world.account.id() + " true SUCCESS CONFIRMED->CANCELLED venue=" + world.venue.id());
        assertThat(refusals(world.account.id())).isEmpty();
        assertThat(pinInAnyAuditPayload()).isZero();
    }

    @Test
    void aMissingPinIsRequiredAndWritesNothing() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);

        for (String missing : new String[]{null, "", "   "}) {
            assertRefused(world, created, missing, ApiErrorCode.OWNER_PASSWORD_REQUIRED);
        }
        assertThat(refusals(world.account.id())).as("no value sent is not a guess: not audited, not counted")
                .isEmpty();
    }

    @Test
    void aWrongPinIsInvalidAuditedAsAFailureAndChangesNothing() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);

        assertRefused(world, created, "9999", ApiErrorCode.OWNER_PASSWORD_INVALID);

        // lockedOut false: the gate checked the PIN and counted it towards the lockout (JdbcOwnerPasswordGate)
        assertThat(refusals(world.account.id())).containsExactly(
                "OWNER_PASSWORD_REFUSED VENUE " + world.account.id() + " FAILURE CANCEL_CONFIRMED_ONLINE_BOOKING false");
        assertThat(audit(created.bookingId())).isEmpty();
        assertThat(pinInAnyAuditPayload()).isZero();

        cancellations.cancel(world.account.id(), created.bookingId(), OWNER_PIN);
        assertThat(booking(created.bookingId()).status()).as("the right PIN still works").isEqualTo("CANCELLED");
    }

    /**
     * PO decision Q2 (24 Oct 2026): the right PIN on a booking that is no longer CONFIRMED is BOOKING_STATE_CHANGED.
     * The cancellation's SUCCESS audit row rolls back with the refused action, and the gate then records, in its own
     * transaction, exactly one OWNER_PASSWORD_ACCEPTED row for the venue account with the protected action and the
     * refusal's code. A right PIN is no refusal: nothing is counted towards the lockout.
     */
    @Test
    void q2TheRightPinOnABookingNoLongerConfirmedLeavesOneAcceptedAuditRow() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = creation.create(world.request(world.anotherCustomer(), world.room, world.start(), 60));
        flows.ownerPin(world.account.id());
        long failuresBefore = failures(world.account.id());

        assertRefused(world, created, OWNER_PIN, ApiErrorCode.BOOKING_STATE_CHANGED);

        assertThat(audit(created.bookingId())).as("the success row rolled back with the 409").isEmpty();
        assertThat(accepted(world.account.id())).as("the gate's one durable row").containsExactly(
                "OWNER_PASSWORD_ACCEPTED VENUE " + world.account.id()
                        + " SUCCESS CANCEL_CONFIRMED_ONLINE_BOOKING BOOKING_STATE_CHANGED");
        assertThat(refusals(world.account.id())).as("a right PIN is no refusal").isEmpty();
        assertThat(failures(world.account.id())).as("not counted").isEqualTo(failuresBefore);
    }

    // ------------------------------------------------------------------------------------------------------ refusals

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "VENUE_RESPONSE_EXPIRED", "AWAITING_CUSTOMER_CONFIRMATION", "ACTIVE", "NO_SHOW",
            "COMPLETED", "REJECTED", "CANCELLED"})
    void everyStatusButConfirmedIsBookingStateChangedAndNothingChanges(String status) {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = creation.create(world.request(world.anotherCustomer(), world.room, world.start(), 60));
        flows.ownerPin(world.account.id());
        switch (status) {
            case "PENDING" -> { }
            case "VENUE_RESPONSE_EXPIRED" -> BookingStates.plant(created.bookingId(),
                    Plant.of(status).expiredAt(minutesFromNow(-10)));
            case "AWAITING_CUSTOMER_CONFIRMATION" -> BookingStates.plant(created.bookingId(),
                    Plant.of(status).expiredAt(minutesFromNow(-20)).reopenedAt(minutesFromNow(-5)));
            case "ACTIVE" -> BookingStates.plant(created.bookingId(), Plant.of(status).startAt(minutesFromNow(-5)));
            case "NO_SHOW", "COMPLETED" -> BookingStates.plant(created.bookingId(),
                    Plant.of(status).startAt(minutesFromNow(-90)).terminalAt(minutesFromNow(-60)));
            case "REJECTED" -> BookingStates.plant(created.bookingId(),
                    Plant.of(status).reason("VENUE_REJECTED").terminalAt(minutesFromNow(-5)));
            default -> BookingStates.plant(created.bookingId(),
                    Plant.of(status).reason("CUSTOMER_CANCELLED").terminalAt(minutesFromNow(-5)));
        }

        assertRefused(world, created, OWNER_PIN, ApiErrorCode.BOOKING_STATE_CHANGED);
    }

    @Test
    void aConfirmedManualBookingIsBookingStateChangedAndUsesCancelManual() {
        Booking manual = flows.manualConfirmed();
        flows.ownerPin(manual.venueAccountId());
        BookingRow before = booking(manual.bookingId());

        assertThatThrownBy(() -> cancellations.cancel(manual.venueAccountId(), manual.bookingId(), OWNER_PIN))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED));
        assertThat(booking(manual.bookingId())).isEqualTo(before);
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, manual.bookingId())).isEmpty();
    }

    @Test
    void anotherVenuesBookingIsNotFoundBeforeAnyPinCheck() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);
        OnlineBookingWorld other = OnlineBookingWorld.create();
        flows.ownerPin(other.account.id());
        BookingRow before = booking(created.bookingId());

        for (String pin : new String[]{OWNER_PIN, "9999"}) {
            assertThatThrownBy(() -> cancellations.cancel(other.account.id(), created.bookingId(), pin))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.code()).isEqualTo(ApiErrorCode.NOT_FOUND));
        }
        assertThatThrownBy(() -> cancellations.cancel(world.account.id(), UUID.randomUUID(), OWNER_PIN))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ApiErrorCode.NOT_FOUND));
        assertThat(booking(created.bookingId())).isEqualTo(before);
        assertThat(refusals(other.account.id())).as("no PIN checked, no attempt counted").isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------- integrity

    /**
     * The reservation's release must happen: a release the ledger reports as not done ({@code false}: no such row, or
     * already released) is an integrity failure (D11 of E7-47) - the whole cancellation rolls back, and one SEV_1
     * alert commits on its own after the rollback (E7-56).
     */
    @Test
    void aReleaseThatReleasesNothingIsAnIntegrityFailureAndRollsEverythingBack() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);
        BookingRow before = booking(created.bookingId());
        VenueConfirmedCancellation cancellation = new VenueConfirmedCancellation(
                context.getBean(VenueAnswerStore.class), context.getBean(VenueBookings.class),
                context.getBean(VenueEligibility.class), new ReleasesNothing(context.getBean(ReservationLedger.class)),
                context.getBean(BookingFeeRefunds.class), context.getBean(ScheduledJobs.class),
                context.getBean(Outbox.class), context.getBean(AdminAlerts.class));
        // The full protected path - the real Owner Password gate, PIN check and audit - around the broken ledger
        VenueConfirmedCancellations protectedPath = new VenueConfirmedCancellations(runner,
                context.getBean(Idempotency.class), context.getBean(VenueAccountVenues.class),
                context.getBean(VenueAnswerStore.class), cancellation, context.getBean(OwnerPasswordGate.class),
                context.getBean(AuditWriter.class));

        assertThatThrownBy(() -> protectedPath.cancel(world.account.id(), created.bookingId(), OWNER_PIN))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("integrity failure")
                .hasMessageContaining("was not released");

        assertNothingChanged(created, before, 2);
        assertThat(reservation(created.reservationId()).active()).as("still holding the room").isTrue();
        assertThat(audit(created.bookingId())).as("the SUCCESS row rolled back with it").isEmpty();
        assertThat(refusals(world.account.id())).as("a right PIN is no refusal").isEmpty();
        assertThat(VenueAnswerDb.outboxTypes(created.bookingId())).doesNotContain(IntegrationEvents.BOOKING_CANCELLED);
        assertThat(job(bookingStart(created.bookingId())).orElseThrow().status()).as("T4 not withdrawn")
                .isEqualTo("PENDING");
        assertThat(VenueAnswerDb.adminAlerts(created.bookingId()))
                .containsExactly("BOOKING_INTEGRITY_FAILURE SEV_1 " + created.bookingId() + " true");
    }

    /**
     * Category B (FRS section 2.3; "no Dashboard operational action while Venue Account DISABLED"): the race of an
     * account disabled after its request passed the token filter. The PIN is checked first, inside the command (the
     * gate's contract, as in E7-113): the right PIN then meets FORBIDDEN and everything rolls back, its SUCCESS row
     * included (the Q2 shape); a wrong PIN is 423 and audited. Nothing is cancelled either way.
     */
    @Test
    void anAccountDisabledAfterTheTokenFilterIsForbiddenAndNothingChanges() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);
        OWNER.sql("UPDATE iam.venue_account SET account_status = 'DISABLED' WHERE id = :id")
                .param("id", world.account.id()).update();

        assertRefused(world, created, OWNER_PIN, ApiErrorCode.FORBIDDEN);
        assertThat(audit(created.bookingId())).isEmpty();
        assertThat(refusals(world.account.id())).isEmpty();

        assertRefused(world, created, "9999", ApiErrorCode.OWNER_PASSWORD_INVALID);
        assertThat(refusals(world.account.id())).hasSize(1);
        assertThat(reservation(created.reservationId()).active()).isTrue();
    }

    @Test
    void aMissingReservationIsAnIntegrityFailureAndRollsEverythingBackTheAuditIncluded() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);
        VenueAnswerDb.reservationLost(created.reservationId());
        BookingRow before = booking(created.bookingId());

        assertThatThrownBy(() -> cancellations.cancel(world.account.id(), created.bookingId(), OWNER_PIN))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("integrity failure");

        assertNothingChanged(created, before, 2);
        assertThat(audit(created.bookingId())).isEmpty();
        assertThat(VenueAnswerDb.adminAlerts(created.bookingId()))
                .containsExactly("BOOKING_INTEGRITY_FAILURE SEV_1 " + created.bookingId() + " true");
    }

    // ----------------------------------------------------------------------------------------------------------- T4

    /**
     * No time limit (PO decision 6 Oct 2026): still CONFIRMED after its start because T4 has not run, the booking is
     * cancelled; its T4 is withdrawn, and firing it afterwards starts nothing.
     */
    @Test
    void aConfirmedBookingPastItsStartIsCancelledAndItsStartNeverRuns() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(5));
        flows.ownerPin(booking.venueAccountId());

        cancellations.cancel(booking.venueAccountId(), booking.bookingId(), OWNER_PIN);

        assertThat(booking(booking.bookingId()).status()).isEqualTo("CANCELLED");
        assertThat(job(bookingStart(booking.bookingId())).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(flows.fireStart(booking)).as("no PENDING start left to fire").isFalse();
        assertThat(booking(booking.bookingId()).status()).isEqualTo("CANCELLED");
        assertThat(events(IntegrationEvents.BOOKING_STARTED, booking.bookingId())).isEmpty();
    }

    /**
     * Still CONFIRMED after its endAt - T4 stranded (E7-60 decision 3 of Amr Heiba, 18 Oct 2026: the late-T4 handling
     * itself is E7-82's / E7-84's): the state rule has no time limit (PO decision 6 Oct 2026), so the booking is
     * cancelled through the database's own guards, nothing relaxed. The room is released at the cancel instant, later
     * than its planned end - the planned interval stays as history (ck_reservation_release_chronology needs only a
     * release after the reservation was taken). Full refund, commitment freed, T4 withdrawn, no start.
     */
    @Test
    void aConfirmedBookingPastItsEndIsStillCancelledUnderTheDatabaseGuards() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(90));
        assertThat(BookingFlows.endAt(booking.bookingId())).as("60 minutes long: ended 30 minutes ago")
                .isBefore(OnlineBookingWorld.dbNow());
        flows.ownerPin(booking.venueAccountId());

        VenueBookingRow row = cancellations.cancel(booking.venueAccountId(), booking.bookingId(), OWNER_PIN);

        assertThat(row.status()).isEqualTo(BookingStatus.CANCELLED);
        BookingRow stored = booking(booking.bookingId());
        assertThat(stored.terminalReason()).isEqualTo("VENUE_CANCELLED_CONFIRMED_BOOKING");
        assertThat(stored.feeTreatment()).isEqualTo("FULL_REFUND");
        assertThat(stored.counted()).isFalse();
        assertThat(stored.terminalAt()).isAfter(BookingFlows.endAt(booking.bookingId()));
        assertThat(OWNER.sql("""
                        SELECT actual_reservation_release_at = CAST(:at AS timestamptz) AND NOT active_flag
                               AND planned_end < actual_reservation_release_at
                          FROM inventory.resource_reservation
                         WHERE booking_id = :id AND reservation_type = 'BOOKING'""")
                .param("at", java.time.OffsetDateTime.ofInstant(stored.terminalAt(), java.time.ZoneOffset.UTC))
                .param("id", booking.bookingId()).query(Boolean.class).single()).isTrue();
        assertThat(job(bookingStart(booking.bookingId())).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(events(IntegrationEvents.BOOKING_STARTED, booking.bookingId())).isEmpty();
        assertThat(count("attendance.attendance_record WHERE booking_id = :id", booking.bookingId())).isZero();
    }

    /** A start a worker already holds is left to it (ScheduledJobs#supersede): its fire re-reads the booking. */
    @Test
    void aStartAWorkerHoldsIsNotTakenFromIt() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(5));
        flows.ownerPin(booking.venueAccountId());
        flows.startLeased(booking);

        cancellations.cancel(booking.venueAccountId(), booking.bookingId(), OWNER_PIN);

        assertThat(booking(booking.bookingId()).status()).isEqualTo("CANCELLED");
        assertThat(job(bookingStart(booking.bookingId())).orElseThrow().status()).isEqualTo("LEASED");
    }

    /** T4 first: the booking started (ACTIVE) and the venue's cancel is BOOKING_STATE_CHANGED - T4 is unchanged. */
    @Test
    void aBookingT4HasStartedIsBookingStateChanged() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(5));
        flows.ownerPin(booking.venueAccountId());
        assertThat(flows.fireStart(booking)).isTrue();
        assertThat(booking(booking.bookingId()).status()).isEqualTo("ACTIVE");
        BookingRow before = booking(booking.bookingId());

        assertThatThrownBy(() -> cancellations.cancel(booking.venueAccountId(), booking.bookingId(), OWNER_PIN))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED));
        assertThat(booking(booking.bookingId())).isEqualTo(before);
        assertThat(events(IntegrationEvents.BOOKING_STARTED, booking.bookingId())).hasSize(1);
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, booking.bookingId())).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    /** A PENDING Online Booking accepted through E7-47's real command; the venue account gets its Owner PIN. */
    private CreatedBooking confirmed(OnlineBookingWorld world) {
        CreatedBooking created = creation.create(world.request(world.anotherCustomer(), world.room, world.start(), 60));
        assertThat(responses.accept(world.account.id(), created.bookingId())).isInstanceOf(CommandAnswer.Ok.class);
        flows.ownerPin(world.account.id());
        return created;
    }

    private void assertRefused(OnlineBookingWorld world, CreatedBooking created, String pin, ApiErrorCode code) {
        BookingRow before = booking(created.bookingId());
        int historyBefore = history(created.bookingId()).size();

        assertThatThrownBy(() -> cancellations.cancel(world.account.id(), created.bookingId(), pin))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
        assertNothingChanged(created, before, historyBefore);
    }

    private static void assertNothingChanged(CreatedBooking created, BookingRow before, int historyRows) {
        assertThat(booking(created.bookingId())).as("nothing changed").isEqualTo(before);
        assertThat(history(created.bookingId())).hasSize(historyRows);
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, created.bookingId())).isEmpty();
        assertThat(refunds(created.bookingId())).isEmpty();
    }

    /** "TREATMENT REASON customerId" of each Booking Fee decision of the booking. */
    private static List<String> refunds(UUID bookingId) {
        return OWNER.sql("""
                        SELECT treatment || ' ' || terminal_reason || ' ' || user_id
                          FROM loyalty.booking_fee_refund WHERE booking_id = :id""")
                .param("id", bookingId).query(String.class).list();
    }

    /** The audit rows about the booking, as "ACTION SUBJECT ACTOR actorId used OUTCOME FROM->TO venue=id". */
    private static List<String> audit(UUID bookingId) {
        return OWNER.sql("""
                        SELECT action || ' ' || subject_type || ' ' || actor_type || ' ' || actor_id || ' '
                               || owner_password_used || ' ' || owner_password_outcome || ' '
                               || previous_state || '->' || new_state || ' venue=' || context_venue_id
                          FROM audit.audit_event WHERE subject_id = :id ORDER BY occurred_at""")
                .param("id", bookingId).query(String.class).list();
    }

    /** The gate's refusals of the account, as "ACTION ACTOR actorId OUTCOME protectedAction lockedOut". */
    private static List<String> refusals(UUID accountId) {
        return OWNER.sql("""
                        SELECT action || ' ' || actor_type || ' ' || actor_id || ' ' || owner_password_outcome || ' '
                               || (payload->>'protectedAction') || ' ' || (payload->>'lockedOut')
                          FROM audit.audit_event
                         WHERE action = 'OWNER_PASSWORD_REFUSED' AND subject_id = :id ORDER BY occurred_at""")
                .param("id", accountId).query(String.class).list();
    }

    /**
     * The gate's acceptance rows of the account (PO decision Q2), as
     * "ACTION ACTOR actorId OUTCOME protectedAction refusalCode".
     */
    private static List<String> accepted(UUID accountId) {
        return OWNER.sql("""
                        SELECT action || ' ' || actor_type || ' ' || actor_id || ' ' || owner_password_outcome || ' '
                               || (payload->>'protectedAction') || ' ' || (payload->>'refusalCode')
                          FROM audit.audit_event
                         WHERE action = 'OWNER_PASSWORD_ACCEPTED' AND subject_id = :id ORDER BY occurred_at""")
                .param("id", accountId).query(String.class).list();
    }

    /** The account's counted wrong PINs: identity's OWNER_PASSWORD rate-limit counter (0 without a row). */
    private static long failures(UUID accountId) {
        return OWNER.sql("""
                        SELECT coalesce((SELECT counter FROM iam.security_rate_limit_counter
                                          WHERE limiter_type = 'OWNER_PASSWORD'
                                            AND subject_key_hash = encode(sha256(convert_to(
                                                    'venue-account:' || CAST(:id AS text), 'UTF8')), 'hex')), 0)""")
                .param("id", accountId).query(Long.class).single();
    }

    /**
     * Audit rows, of any test, with the PIN as a payload value at any depth. A value equal to it, not the four digits
     * anywhere in the text: a UUID or a timestamp's microseconds in another row may contain them by chance.
     */
    private static long pinInAnyAuditPayload() {
        return OWNER.sql("""
                        SELECT count(*) FROM audit.audit_event
                         WHERE jsonb_path_exists(payload, '$.** ? (@ == $pin)', jsonb_build_object('pin', :pin))""")
                .param("pin", OWNER_PIN).query(Long.class).single();
    }

    private static long count(String fromWhere, UUID id) {
        return VenueAnswerDb.count(fromWhere, id);
    }

    private static int notifications(UUID bookingId) {
        return OWNER.sql("SELECT count(*) FROM notification.notification WHERE booking_id = :id")
                .param("id", bookingId).query(Integer.class).single();
    }

    /** The real ledger, except that a release reports it released nothing - as for a row already released. */
    private record ReleasesNothing(ReservationLedger ledger) implements ReservationLedger {

        @Override
        public UUID create(CommandContext command, NewReservation reservation) {
            return ledger.create(command, reservation);
        }

        @Override
        public boolean release(CommandContext command, UUID reservationId, Instant releaseAt, String releaseReason) {
            return false;
        }

        @Override
        public Optional<ActiveReservation> activeOf(CommandContext command, ReservationOwner owner) {
            return ledger.activeOf(command, owner);
        }

        @Override
        public boolean hasBlockingOverlap(CommandContext command, UUID resourceId, Instant start, Instant end) {
            return ledger.hasBlockingOverlap(command, resourceId, start, end);
        }
    }
}
