package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.booking.internal.BookingStates.minutesFromNow;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.bookingStart;
import static ps.e7gz.booking.internal.VenueAnswerDb.events;
import static ps.e7gz.booking.internal.VenueAnswerDb.history;
import static ps.e7gz.booking.internal.VenueAnswerDb.job;
import static ps.e7gz.booking.internal.VenueAnswerDb.outboxTypes;
import static ps.e7gz.booking.internal.VenueAnswerDb.reservation;
import static ps.e7gz.booking.internal.VenueAnswerDb.t1;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import ps.e7gz.booking.internal.BookingStates.Plant;
import ps.e7gz.booking.internal.VenueAnswerDb.BookingRow;
import ps.e7gz.booking.internal.VenueAnswerDb.HistoryRow;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.messaging.api.CommandAnswer;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-49 - the customer's cancellation on the real database (FR-BOK-31, FR-BOK-32; PO decision 11 Oct 2026, E7-49, D2
 * and D4): one transaction releases the reservation at the command instant, records exactly one CUSTOMER_CANCELLED
 * with its fee decision, appends the history row, supersedes the booking's jobs, ends an emergency pause, and writes
 * one {@code BookingCancelled} - and no customer notification (E7-55's). Every refusal changes nothing.
 */
class CustomerCancellationIntegrationTest extends IntegrationTest {

    @Autowired
    OnlineBookingCreation creation;

    @Autowired
    CustomerBookings bookings;

    @Autowired
    VenueResponses responses;

    private CorrelationId.Scope correlation;

    @BeforeEach
    void openCorrelation() {
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    @Test
    void aPendingBookingIsCancelledAtomicallyWithEveryEffect() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);

        CustomerBookingRow row = bookings.cancel(created.customerId(), created.bookingId());

        assertThat(row.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(row.displayBucket()).isEqualTo(DisplayBucket.CANCELLED);
        assertThat(row.terminalReason()).isEqualTo(BookingTerminalReason.CUSTOMER_CANCELLED);
        assertThat(row.venuePhone()).as("visible until terminalAt + 2h").isNotNull();
        assertThat(row.venueResponseExpiresAt()).isNull();
        BookingRow stored = booking(created.bookingId());
        assertThat(stored.status()).isEqualTo("CANCELLED");
        assertThat(stored.terminalReason()).isEqualTo("CUSTOMER_CANCELLED");
        assertThat(stored.feeTreatment()).as("FR-PTS-16").isEqualTo("CUSTOMER_POLICY");
        assertThat(stored.counted()).as("the commitment slot is free again").isFalse();
        assertThat(reservation(created.reservationId())).satisfies(released -> {
            assertThat(released.active()).isFalse();
            assertThat(released.releasedAt()).as("released at the cancel instant").isEqualTo(stored.terminalAt());
            assertThat(released.releaseReason()).isEqualTo("CUSTOMER_CANCELLED");
        });
        List<HistoryRow> history = history(created.bookingId());
        assertThat(history).hasSize(2);
        assertThat(history.get(1)).satisfies(h -> {
            assertThat(h.fromStatus()).isEqualTo("PENDING");
            assertThat(h.toStatus()).isEqualTo("CANCELLED");
            assertThat(h.terminalReason()).isEqualTo("CUSTOMER_CANCELLED");
            assertThat(h.actorType()).isEqualTo("CUSTOMER");
            assertThat(h.actorId()).isEqualTo(created.customerId());
            assertThat(h.occurredAt()).isEqualTo(stored.terminalAt());
        });
        assertThat(row.history()).hasSize(2);
        assertThat(refunds(created.bookingId())).as("one fee decision").isEqualTo(1);
        assertThat(job(t1(created.bookingId())).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, created.bookingId())).singleElement()
                .satisfies(payload -> assertThat(payload)
                        .contains("\"bookingId\": \"" + created.bookingId() + "\"")
                        .contains("\"customerId\": \"" + created.customerId() + "\"")
                        .contains("\"terminalReason\": \"CUSTOMER_CANCELLED\""));
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, created.reservationId())).hasSize(1);
        assertThat(outboxTypes(created.bookingId())).containsExactly(IntegrationEvents.BOOKING_CREATED,
                IntegrationEvents.BOOKING_CANCELLED);
        assertThat(notifications(created.bookingId())).as("D4: the customer notification is E7-55's").isZero();
    }

    @Test
    void aConfirmedBookingIsCancelledAndItsStartJobWithdrawn() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        assertThat(responses.accept(world.account.id(), created.bookingId())).isInstanceOf(CommandAnswer.Ok.class);
        assertThat(job(bookingStart(created.bookingId())).orElseThrow().status()).isEqualTo("PENDING");

        CustomerBookingRow row = bookings.cancel(created.customerId(), created.bookingId());

        assertThat(row.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(history(created.bookingId()).get(2).fromStatus()).isEqualTo("CONFIRMED");
        assertThat(reservation(created.reservationId()).active()).isFalse();
        assertThat(job(bookingStart(created.bookingId())).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(booking(created.bookingId()).feeTreatment()).isEqualTo("CUSTOMER_POLICY");
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, created.bookingId())).hasSize(1);
    }

    @Test
    void anEmergencyPausedPendingBookingIsCancelledAndItsPauseClosed() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        VenueAnswerDb.venueEmergency(world.venue.id());
        VenueAnswerDb.paused(created.bookingId());

        CustomerBookingRow row = bookings.cancel(created.customerId(), created.bookingId());

        assertThat(row.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(row.venueResponsePaused()).isFalse();
        assertThat(VenueAnswerDb.responsePaused(created.bookingId())).isFalse();
        assertThat(VenueAnswerDb.OWNER.sql("""
                        SELECT count(*) FROM operations.booking_timer_pause_history
                         WHERE booking_id = :id AND pause_kind = 'VENUE_RESPONSE' AND pause_ended_at IS NULL""")
                .param("id", created.bookingId()).query(Integer.class).single()).as("no open pause").isZero();
        assertThat(VenueAnswerDb.OWNER.sql("""
                        SELECT cumulative_emergency_pause_duration >= interval '0' FROM booking.booking WHERE id = :id""")
                .param("id", created.bookingId()).query(Boolean.class).single()).isTrue();
        assertThat(job(t1(created.bookingId())).orElseThrow().status()).as("the PAUSED T1 is withdrawn")
                .isEqualTo("SUPERSEDED");
        assertThat(reservation(created.reservationId()).active()).isFalse();
        VenueAnswerDb.endEmergencies(world.venue.id());
    }

    @Test
    void theCancelledSlotAndTheCommitmentAreFreeAtOnce() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);

        bookings.cancel(created.customerId(), created.bookingId());

        CreatedBooking again = creation.create(world.request(created.customerId(), world.room, created.startAt(), 60));
        assertThat(booking(again.bookingId()).status()).as("same customer, same room, same interval")
                .isEqualTo("PENDING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"VENUE_RESPONSE_EXPIRED", "AWAITING_CUSTOMER_CONFIRMATION", "NO_SHOW", "COMPLETED",
            "REJECTED", "CANCELLED"})
    void everyOtherStatusIsBookingStateChangedAndNothingChanges(String status) {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        BookingStates.plant(created.bookingId(), switch (status) {
            case "VENUE_RESPONSE_EXPIRED" -> Plant.of(status).expiredAt(minutesFromNow(-10));
            case "AWAITING_CUSTOMER_CONFIRMATION" -> Plant.of(status).expiredAt(minutesFromNow(-20))
                    .reopenedAt(minutesFromNow(-5));
            case "NO_SHOW", "COMPLETED" -> Plant.of(status).startAt(minutesFromNow(-90))
                    .terminalAt(minutesFromNow(-60));
            case "REJECTED" -> Plant.of(status).reason("VENUE_REJECTED").terminalAt(minutesFromNow(-5));
            default -> Plant.of(status).reason("CUSTOMER_CANCELLED").terminalAt(minutesFromNow(-5));
        });

        assertRefused(created, ApiErrorCode.BOOKING_STATE_CHANGED);
    }

    @Test
    void anActiveBookingIsDeadlinePassed() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        BookingStates.plant(created.bookingId(), Plant.of("ACTIVE").startAt(minutesFromNow(-5)));

        assertRefused(created, ApiErrorCode.DEADLINE_PASSED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "CONFIRMED"})
    void aBookingWhoseStartHasPassedButNotYetStartedIsDeadlinePassed(String status) {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        // the start job has not run yet: still PENDING / CONFIRMED, but now >= startAt (FR-AVL-15)
        BookingStates.plant(created.bookingId(), Plant.of(status).startAt(minutesFromNow(-1)));

        assertRefused(created, ApiErrorCode.DEADLINE_PASSED);
    }

    @Test
    void anotherCustomersBookingIsNotFoundAndNothingChanges() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);

        assertThatThrownBy(() -> bookings.cancel(world.anotherCustomer(), created.bookingId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ApiErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> bookings.cancel(created.customerId(), UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ApiErrorCode.NOT_FOUND));
        assertThat(booking(created.bookingId()).status()).isEqualTo("PENDING");
    }

    @Test
    void aMissingReservationIsAnIntegrityFailureAndRollsEverythingBack() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        VenueAnswerDb.reservationLost(created.reservationId());

        assertThatThrownBy(() -> bookings.cancel(created.customerId(), created.bookingId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("integrity failure");
        assertThat(booking(created.bookingId()).status()).isEqualTo("PENDING");
        assertThat(history(created.bookingId())).hasSize(1);
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, created.bookingId())).isEmpty();
        assertThat(refunds(created.bookingId())).isZero();
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private CreatedBooking pending(OnlineBookingWorld world) {
        return creation.create(world.request(world.anotherCustomer(), world.room, world.start(), 60));
    }

    private void assertRefused(CreatedBooking created, ApiErrorCode code) {
        BookingRow before = booking(created.bookingId());
        int historyBefore = history(created.bookingId()).size();

        assertThatThrownBy(() -> bookings.cancel(created.customerId(), created.bookingId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
        assertThat(booking(created.bookingId())).as("nothing changed").isEqualTo(before);
        assertThat(history(created.bookingId())).hasSize(historyBefore);
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, created.bookingId())).isEmpty();
    }

    private static int refunds(UUID bookingId) {
        return VenueAnswerDb.OWNER.sql("SELECT count(*) FROM loyalty.booking_fee_refund WHERE booking_id = :id")
                .param("id", bookingId).query(Integer.class).single();
    }

    private static int notifications(UUID bookingId) {
        return VenueAnswerDb.OWNER.sql("SELECT count(*) FROM notification.notification WHERE booking_id = :id")
                .param("id", bookingId).query(Integer.class).single();
    }
}
