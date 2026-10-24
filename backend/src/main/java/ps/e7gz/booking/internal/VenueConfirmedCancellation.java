package ps.e7gz.booking.internal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ps.e7gz.admin.api.AdminAlerts;
import ps.e7gz.admin.api.NewAdminAlert;
import ps.e7gz.booking.internal.VenueAnswerStore.Facts;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.inventory.ledger.api.ActiveReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.loyalty.api.BookingFeeRefunds;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.venue.api.VenueEligibility;

/**
 * The venue cancels a CONFIRMED Online Booking: CONFIRMED -> CANCELLED / VENUE_CANCELLED_CONFIRMED_BOOKING (FR-VEN-03,
 * FRS section 2.1 state table, E7-114). A command body: it runs after {@code begin_command} has locked the booking's
 * Customer (with their Bookings, commitment guard and Bans), Venue and Resource, and after the Owner Password has been
 * checked in the same transaction ({@link VenueConfirmedCancellations}); it re-reads the booking and decides from that
 * state.
 *
 * <ul>
 *   <li>A booking of another venue: {@code NOT_FOUND}.</li>
 *   <li>The venue account not ENABLED: {@code FORBIDDEN} (category B, FRS section 2.3; the race of an account disabled
 *       between the request and the command, as for the venue's other answers).</li>
 *   <li>A Manual Booking (it uses cancel-manual, FR-MAN-08) or any status but CONFIRMED - PENDING, ACTIVE, already
 *       cancelled, ...: {@code BOOKING_STATE_CHANGED} (Sprint 3 contract).</li>
 * </ul>
 * No time check (PO decision 6 Oct 2026): a CONFIRMED booking is cancelled even after its {@code startAt}, while the
 * start (T4) has not run. An ACTIVE one is never cancelled here (operational termination is Sprint 5's).
 *
 * <p>One instant, {@code now}, for every write: {@code terminal_at}, the reservation's release (the room is sellable
 * again from the cancel instant) and the history row. The database stays the authority on the transition
 * ({@code tr_03_booking_transition_guard}), the single reason, the fee treatment (FULL_REFUND, FR-PTS-15, from
 * {@code booking.expected_fee_treatment}) and {@code commitment_period}, which frees the Customer's commitment slot.
 * The Booking Fee decision is recorded through Loyalty. The reservation's release must happen: a row that is not
 * released is an integrity failure (D11 of E7-47), rolled back with its SEV_1 alert. No Trust input, no no-show and
 * no attendance write: a venue-caused cancellation carries no customer penalty. The customer notification is E7-55's
 * consumer's, from {@code BookingCancelled} (PO decision 12 Oct 2026).
 */
@Component
class VenueConfirmedCancellation {

    private static final Logger log = LoggerFactory.getLogger(VenueConfirmedCancellation.class);
    static final BookingTerminalReason REASON = BookingTerminalReason.VENUE_CANCELLED_CONFIRMED_BOOKING;

    private final VenueAnswerStore store;
    private final VenueBookings bookings;
    private final VenueEligibility venues;
    private final ReservationLedger ledger;
    private final BookingFeeRefunds refunds;
    private final ScheduledJobs jobs;
    private final Outbox outbox;
    private final AdminAlerts alerts;

    VenueConfirmedCancellation(VenueAnswerStore store, VenueBookings bookings, VenueEligibility venues,
                               ReservationLedger ledger, BookingFeeRefunds refunds, ScheduledJobs jobs, Outbox outbox,
                               AdminAlerts alerts) {
        this.store = store;
        this.bookings = bookings;
        this.venues = venues;
        this.ledger = ledger;
        this.refunds = refunds;
        this.jobs = jobs;
        this.outbox = outbox;
        this.alerts = alerts;
    }

    VenueBookingRow cancel(CommandContext context, UUID bookingId, UUID venueId, UUID venueAccountId) {
        Objects.requireNonNull(venueAccountId, "venueAccountId");
        Instant now = context.commandNow();
        Facts booking = store.facts(context, bookingId)
                .filter(found -> found.venueId().equals(venueId))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        VenueEligibility.Eligibility venue = venues.find(context, venueId)
                .orElseThrow(() -> new IllegalStateException("Venue " + venueId + " vanished"));
        if (venue.accountStatus() != VenueAccountStatus.ENABLED) {
            throw new ApiException(ApiErrorCode.FORBIDDEN);
        }
        if (booking.source() != BookingSource.ONLINE || booking.status() != BookingStatus.CONFIRMED) {
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }

        ActiveReservation reservation = ownReservation(context, booking);
        if (!store.terminate(context, bookingId, BookingStatus.CONFIRMED, BookingStatus.CANCELLED, REASON, now, null)) {
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }
        if (!ledger.release(context, reservation.id(), now, REASON.name())) {
            throw integrityFailure(bookingId, "the booking's reservation was not released");
        }
        UUID history = store.insertTransition(context, bookingId, now, BookingStatus.CONFIRMED,
                BookingStatus.CANCELLED, REASON, ActorType.VENUE, venueAccountId, CorrelationId.require());
        refunds.recordDecision(context, booking.customerId(), bookingId, history);
        // The jobs a CONFIRMED booking has: T4 (and T5, if one was ever scheduled). A job a worker holds LEASED is left
        // to it: its fire re-reads the booking, no longer CONFIRMED, and supersedes itself (E7-60, E7-61).
        jobs.supersede(context, ManualBookingCommand.bookingStartKey(bookingId));
        jobs.supersede(context, ManualBookingCommand.bookingCompleteKey(bookingId));
        outbox.publish(context, BookingEvents.cancelled(booking, REASON));
        return bookings.current(context, bookingId);
    }

    /**
     * The booking's own active BOOKING reservation on exactly its Resource and interval. A CONFIRMED booking without it
     * is a data defect (D11 of E7-47): ERROR, 500, rolled back - never a refusal the venue could act on.
     */
    private ActiveReservation ownReservation(CommandContext context, Facts booking) {
        ActiveReservation reservation = ledger.activeOf(context, ReservationOwner.booking(booking.bookingId()))
                .orElseThrow(() -> integrityFailure(booking.bookingId(),
                        booking.status() + " booking holds no active reservation"));
        if (!reservation.resourceId().equals(booking.resourceId())
                || !reservation.plannedStart().equals(booking.startAt())
                || !reservation.plannedEnd().equals(booking.endAt())) {
            throw integrityFailure(booking.bookingId(),
                    "the reservation does not match the booking's Resource and interval");
        }
        return reservation;
    }

    private IllegalStateException integrityFailure(UUID bookingId, String detail) {
        log.error("Booking integrity failure: {} (bookingId={}, correlationId={})", detail, bookingId,
                CorrelationId.current().map(UUID::toString).orElse("none"));
        // E7-56 (PO decisions 10, 12 and 15 Oct 2026): SEV_1, committed on its own once this command has rolled back
        CorrelationId.current().ifPresent(correlation -> alerts.raiseAfterRollback(
                NewAdminAlert.bookingIntegrityFailure(bookingId, detail, correlation)));
        return new IllegalStateException("Booking " + bookingId + " integrity failure: " + detail);
    }
}
