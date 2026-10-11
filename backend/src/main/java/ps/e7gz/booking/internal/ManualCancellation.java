package ps.e7gz.booking.internal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
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
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.venue.api.VenueEligibility;

/**
 * The venue cancels a Manual Booking: CONFIRMED or ACTIVE -> CANCELLED / VENUE_CANCELLED_MANUAL_BOOKING, without the
 * Owner Password (FR-MAN-08 as amended by PCR-007; PO decision 10 Oct 2026, E7-48, D5). A command body: it runs after
 * {@code begin_command} has locked the booking's Venue and Resource, re-reads the booking and decides from that state
 * and {@link CommandContext#commandNow()}.
 *
 * <p>One instant, {@code now}, for every write, so they agree: {@code terminal_at}, the reservation's release (the room
 * is free from the cancel instant) and the history row's {@code occurred_at}. On an ACTIVE booking the database checks
 * at commit that exactly one ACTIVE -> CANCELLED history row by an account of this venue goes with it
 * ({@code tr_booking_manual_active_cancel_provenance}, v1.1.34) - the recorded actor, not an authenticated identity.
 * There is no customer, so no Booking Fee decision (fee treatment NOT_APPLICABLE, {@code loyalty.assert_booking} skips
 * MANUAL), no Trust and no points.
 *
 * <p>No time check (PCR-007: "before or after startAt"). An ACTIVE booking whose {@code endAt} has passed but whose
 * BOOKING_COMPLETE has not run yet is still ACTIVE, so it is cancelled too: the rule is the state, as approved -
 * PO decision 11 Oct 2026, E7-48, D12.
 */
@Component
class ManualCancellation {

    private static final Logger log = LoggerFactory.getLogger(ManualCancellation.class);
    private static final BookingTerminalReason REASON = BookingTerminalReason.VENUE_CANCELLED_MANUAL_BOOKING;

    private final VenueAnswerStore store;
    private final VenueBookings bookings;
    private final ReservationLedger ledger;
    private final ScheduledJobs jobs;
    private final Outbox outbox;
    private final VenueEligibility venues;

    ManualCancellation(VenueAnswerStore store, VenueBookings bookings, ReservationLedger ledger, ScheduledJobs jobs,
                       Outbox outbox, VenueEligibility venues) {
        this.store = store;
        this.bookings = bookings;
        this.ledger = ledger;
        this.jobs = jobs;
        this.outbox = outbox;
        this.venues = venues;
    }

    VenueBookingRow cancel(CommandContext context, UUID bookingId, UUID venueId, UUID venueAccountId) {
        Objects.requireNonNull(venueAccountId, "venueAccountId");
        Instant now = context.commandNow();
        Facts booking = store.facts(context, bookingId)
                .filter(found -> found.venueId().equals(venueId))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        // Category C (FRS 2.3, ADR-022): the venue account ENABLED, read under the command's lock on the Venue and its
        // account. The token filter refuses a disabled account first (401); this is the race of an account disabled
        // between the request and the command - FORBIDDEN before any write, as for creation (PO decision 10 Oct 2026,
        // E7-48, D4)
        VenueEligibility.Eligibility venue = venues.find(context, venueId)
                .orElseThrow(() -> new IllegalStateException("Venue " + venueId + " vanished"));
        if (venue.accountStatus() != VenueAccountStatus.ENABLED) {
            throw new ApiException(ApiErrorCode.FORBIDDEN);
        }
        // D5: an ONLINE booking, or one already CANCELLED or COMPLETED, is BOOKING_STATE_CHANGED
        BookingStatus from = booking.status();
        if (booking.source() != BookingSource.MANUAL
                || (from != BookingStatus.CONFIRMED && from != BookingStatus.ACTIVE)) {
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }

        if (!store.terminate(context, bookingId, from, BookingStatus.CANCELLED, REASON, now, null)) {
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }
        ActiveReservation reservation = ledger.activeOf(context, ReservationOwner.booking(bookingId))
                .orElseThrow(() -> integrityFailure(bookingId, from + " Manual Booking holds no active reservation"));
        ledger.release(context, reservation.id(), now, REASON.name());
        // The jobs this booking still has: T4 while CONFIRMED (and T5 if E7-60 ever scheduled it), T5 once ACTIVE. A job
        // a worker holds LEASED is left to it: its fire re-reads the booking (E7-60 / E7-61 carry-forward).
        if (from == BookingStatus.CONFIRMED) {
            jobs.supersede(context, ManualBookingCommand.bookingStartKey(bookingId));
        }
        jobs.supersede(context, ManualBookingCommand.bookingCompleteKey(bookingId));
        store.insertTransition(context, bookingId, now, from, BookingStatus.CANCELLED, REASON, ActorType.VENUE,
                venueAccountId, CorrelationId.require());
        outbox.publish(context, BookingEvents.manualCancelled(booking, REASON));
        return bookings.current(context, bookingId);
    }

    /** D11 (E7-47): a CONFIRMED or ACTIVE booking without its reservation is a data defect - ERROR, 500, rolled back. */
    private static IllegalStateException integrityFailure(UUID bookingId, String detail) {
        log.error("Booking integrity failure: {} (bookingId={}, correlationId={})", detail, bookingId,
                CorrelationId.current().map(UUID::toString).orElse("none"));
        return new IllegalStateException("Booking " + bookingId + " integrity failure: " + detail);
    }
}
