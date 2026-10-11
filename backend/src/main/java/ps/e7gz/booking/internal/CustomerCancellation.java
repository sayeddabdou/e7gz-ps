package ps.e7gz.booking.internal;

import java.time.Duration;
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
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.inventory.ledger.api.ActiveReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.loyalty.api.BookingFeeRefunds;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.operations.api.ResponseTimerPauses;
import ps.e7gz.scheduling.api.ScheduledJobs;

/**
 * The customer cancels their Online Booking before its start: PENDING or CONFIRMED -> CANCELLED / CUSTOMER_CANCELLED,
 * free and without penalty (FR-BOK-31, FR-BOK-32, FRS F-10 and section 2.1). A command body: it runs after
 * {@code begin_command} has locked the booking's Customer (with their Bookings, commitment guard and Bans), Venue and
 * Resource, re-reads the booking and decides from that state and {@link CommandContext#commandNow()}.
 *
 * <p>PO decision 11 Oct 2026, E7-49 (D2):
 * <ul>
 *   <li>PENDING or CONFIRMED with {@code now < startAt}: cancelled - also a PENDING whose response timer an emergency
 *       pauses (FR-BOK-32): the pause ends with the cancellation, as with the venue's Reject.</li>
 *   <li>ACTIVE, or PENDING / CONFIRMED at or after {@code startAt}: {@code DEADLINE_PASSED} (FR-AVL-15: at the boundary
 *       the start owns the state).</li>
 *   <li>VENUE_RESPONSE_EXPIRED (no customer opt-out, FR-BOK-20), AWAITING_CUSTOMER_CONFIRMATION (Decline is the
 *       customer's action there, FR-LCF-13) and every terminal status - NO_SHOW, COMPLETED, REJECTED, CANCELLED:
 *       {@code BOOKING_STATE_CHANGED} (PO decision 5 Oct 2026).</li>
 *   <li>Another customer's booking, or a Manual Booking: {@code NOT_FOUND}.</li>
 * </ul>
 * A banned Customer may still cancel (FRS section 2.11): no account or Ban check here.
 *
 * <p>One instant, {@code now}, for every write: {@code terminal_at}, the reservation's release (the room is sellable
 * again from the cancel instant), the history row and the end of a pause. The database stays the authority on the
 * transition ({@code tr_03_booking_transition_guard}, which also refuses CUSTOMER_CANCELLED at or after start), the
 * single reason ({@code ck_booking_terminal_reason}), the fee treatment and {@code commitment_period}, which frees the
 * Customer's commitment slot. The Booking Fee decision is recorded (CUSTOMER_POLICY, FR-PTS-16). The customer
 * notification is E7-55's (PO decision 11 Oct 2026, E7-49, D4): this command writes the {@code BookingCancelled}
 * integration event only, which the venue's live channel already signals.
 */
@Component
class CustomerCancellation {

    private static final Logger log = LoggerFactory.getLogger(CustomerCancellation.class);
    private static final BookingTerminalReason REASON = BookingTerminalReason.CUSTOMER_CANCELLED;

    private final VenueAnswerStore store;
    private final ReservationLedger ledger;
    private final BookingFeeRefunds refunds;
    private final ScheduledJobs jobs;
    private final Outbox outbox;
    private final ResponseTimerPauses pauses;

    CustomerCancellation(VenueAnswerStore store, ReservationLedger ledger, BookingFeeRefunds refunds,
                         ScheduledJobs jobs, Outbox outbox, ResponseTimerPauses pauses) {
        this.store = store;
        this.ledger = ledger;
        this.refunds = refunds;
        this.jobs = jobs;
        this.outbox = outbox;
        this.pauses = pauses;
    }

    void cancel(CommandContext context, UUID bookingId, UUID customerId) {
        Objects.requireNonNull(customerId, "customerId");
        Instant now = context.commandNow();
        Facts booking = store.facts(context, bookingId)
                .filter(found -> found.source() == BookingSource.ONLINE && customerId.equals(found.customerId()))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        BookingStatus from = booking.status();
        switch (from) {
            case PENDING, CONFIRMED -> {
                if (!now.isBefore(booking.startAt())) {
                    throw new ApiException(ApiErrorCode.DEADLINE_PASSED);
                }
            }
            case ACTIVE -> throw new ApiException(ApiErrorCode.DEADLINE_PASSED);
            case VENUE_RESPONSE_EXPIRED, AWAITING_CUSTOMER_CONFIRMATION, NO_SHOW, COMPLETED, REJECTED, CANCELLED ->
                    throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }

        if (from == BookingStatus.PENDING && booking.responsePaused()) {
            // FR-BOK-32: cancellable while paused; the pause ends now (FR-OPS-37, FR-OPS-41), as with Reject (E7-47)
            Duration accumulated = store.endResponsePause(context, bookingId, now)
                    .orElseThrow(() -> new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED));
            pauses.closeOpen(context, bookingId, now, accumulated);
        }
        ActiveReservation reservation = ownReservation(context, booking);
        if (!store.terminate(context, bookingId, from, BookingStatus.CANCELLED, REASON, now, null)) {
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }
        ledger.release(context, reservation.id(), now, REASON.name());
        UUID history = store.insertTransition(context, bookingId, now, from, BookingStatus.CANCELLED, REASON,
                ActorType.CUSTOMER, customerId, CorrelationId.require());
        refunds.recordDecision(context, customerId, bookingId, history);
        // The jobs this booking still has: T1 while PENDING; T4 while CONFIRMED (and T5, if one was ever scheduled).
        // A job a worker holds LEASED is left to it: its fire re-reads the booking.
        if (from == BookingStatus.PENDING) {
            jobs.supersede(context, VenueAnswers.venueResponseExpiryKey(bookingId));
        } else {
            jobs.supersede(context, ManualBookingCommand.bookingStartKey(bookingId));
            jobs.supersede(context, ManualBookingCommand.bookingCompleteKey(bookingId));
        }
        outbox.publish(context, BookingEvents.cancelled(booking, REASON));
    }

    /**
     * The booking's own active BOOKING reservation on exactly its Resource and interval. A PENDING or CONFIRMED booking
     * without it is a data defect (D11 of E7-47): ERROR, 500, rolled back - never a refusal the customer could act on.
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

    private static IllegalStateException integrityFailure(UUID bookingId, String detail) {
        log.error("Booking integrity failure: {} (bookingId={}, correlationId={})", detail, bookingId,
                CorrelationId.current().map(UUID::toString).orElse("none"));
        return new IllegalStateException("Booking " + bookingId + " integrity failure: " + detail);
    }
}
