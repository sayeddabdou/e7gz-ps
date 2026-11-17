package ps.e7gz.booking.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.LateConfirmClosures;
import ps.e7gz.booking.internal.VenueAnswerStore.Facts;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.inventory.ledger.api.ActiveReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.loyalty.api.BookingFeeRefunds;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.operations.api.ResponseTimerPauses;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.trust.api.BanCascadeBookings;

/**
 * The Booking side of a Ban activation (O14; arch 07 section 5.4, FR-TRS-20; E7-71): each registered target ends
 * CANCELLED / {@code USER_BANNED} at the activation instant, in the activating command - the same steps as an
 * opening-hours cancellation ({@link HoursChangeCancellation}, E7-113) under another reason and a SYSTEM actor:
 * <ul>
 *   <li>PENDING (an emergency-paused response timer's pause ended first, FR-OPS-37), AWAITING_CUSTOMER_CONFIRMATION
 *       (its Late Confirmation closed and hold released through {@link LateConfirmClosures}) and future CONFIRMED;</li>
 *   <li>the transition and its history row under the command's correlation - the one
 *       {@code trust.assert_ban_target_completion} matches to the Ban's cascade proof - its own BOOKING reservation
 *       released at the activation instant (an AWAITING one's hold went with the closure), its jobs withdrawn and one
 *       {@code BookingCancelled}; the customer and the venue are told from it (E7-55's consumer);</li>
 *   <li>last, the Booking Fee decisions of the batch in one {@link BookingFeeRefunds#recordDecisions}, which joins the
 *       command's Loyalty context when one is open (an Admin correction's), or opens it. USER_BANNED is the customer
 *       policy treatment ({@code booking.expected_fee_treatment}).</li>
 * </ul>
 * ACTIVE bookings are never targets: they continue (FR-TRS-20). A target in any other state, of another customer or
 * not after the activation instant, is a data defect: {@link IllegalStateException}, the whole command rolls back.
 */
@Component
class BanCascadeCancellation implements BanCascadeBookings {

    private static final Logger log = LoggerFactory.getLogger(BanCascadeCancellation.class);
    static final BookingTerminalReason REASON = BookingTerminalReason.USER_BANNED;

    private final VenueAnswerStore store;
    private final ReservationLedger ledger;
    private final BookingFeeRefunds refunds;
    private final ScheduledJobs jobs;
    private final Outbox outbox;
    private final ResponseTimerPauses pauses;
    private final LateConfirmClosures closures;

    BanCascadeCancellation(VenueAnswerStore store, ReservationLedger ledger, BookingFeeRefunds refunds,
                           ScheduledJobs jobs, Outbox outbox, ResponseTimerPauses pauses,
                           LateConfirmClosures closures) {
        this.store = store;
        this.ledger = ledger;
        this.refunds = refunds;
        this.jobs = jobs;
        this.outbox = outbox;
        this.pauses = pauses;
        this.closures = closures;
    }

    @Override
    public void cancel(CommandContext context, UUID customerId, List<UUID> bookingIds, Instant at) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(at, "at");
        List<BookingFeeRefunds.Decision> decisions = new ArrayList<>();
        for (UUID bookingId : HoursChangeCancellation.ordered(bookingIds)) {
            Facts booking = store.facts(context, bookingId)
                    .orElseThrow(() -> integrityFailure(bookingId, "no such booking"));
            BookingStatus from = booking.status();
            boolean target = booking.source() == BookingSource.ONLINE && customerId.equals(booking.customerId())
                    && switch (from) {
                        case PENDING, AWAITING_CUSTOMER_CONFIRMATION -> true;
                        case CONFIRMED -> booking.startAt().isAfter(at);
                        default -> false;
                    };
            if (!target) {
                throw integrityFailure(bookingId, from + " " + booking.source() + " booking is not a Ban target");
            }
            ActiveReservation reservation = from == BookingStatus.AWAITING_CUSTOMER_CONFIRMATION ? null
                    : ledger.activeOf(context, ReservationOwner.booking(bookingId))
                            .orElseThrow(() -> integrityFailure(bookingId, from + " booking holds no active reservation"));
            if (from == BookingStatus.AWAITING_CUSTOMER_CONFIRMATION) {
                closures.closeForCancellation(context, bookingId, REASON, at);
            }
            if (from == BookingStatus.PENDING && booking.responsePaused()) {
                Duration accumulated = store.endResponsePause(context, bookingId, at)
                        .orElseThrow(() -> integrityFailure(bookingId, "the paused response timer did not end"));
                pauses.closeOpen(context, bookingId, at, accumulated);
            }
            if (!store.terminate(context, bookingId, from, BookingStatus.CANCELLED, REASON, at, null)) {
                throw integrityFailure(bookingId, "the booking left " + from + " under the command's locks");
            }
            if (reservation != null) {
                ledger.release(context, reservation.id(), at, REASON.name());
            }
            UUID history = store.insertTransition(context, bookingId, at, from, BookingStatus.CANCELLED, REASON,
                    ActorType.SYSTEM, null, CorrelationId.require());
            if (from == BookingStatus.PENDING) {
                jobs.supersede(context, VenueAnswers.venueResponseExpiryKey(bookingId));
            } else if (from == BookingStatus.CONFIRMED) {
                jobs.supersede(context, ManualBookingCommand.bookingStartKey(bookingId));
                jobs.supersede(context, ManualBookingCommand.bookingCompleteKey(bookingId));
            }
            decisions.add(new BookingFeeRefunds.Decision(customerId, bookingId, history));
            outbox.publish(context, BookingEvents.cancelled(booking, REASON));
        }
        refunds.recordDecisions(context, decisions);
    }

    private static IllegalStateException integrityFailure(UUID bookingId, String detail) {
        log.error("Booking integrity failure: {} (bookingId={}, correlationId={})", detail, bookingId,
                CorrelationId.current().map(UUID::toString).orElse("none"));
        return new IllegalStateException("Booking " + bookingId + " integrity failure in the Ban cascade: " + detail);
    }
}
