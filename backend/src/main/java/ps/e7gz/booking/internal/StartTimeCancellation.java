package ps.e7gz.booking.internal;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ps.e7gz.admin.api.AdminAlerts;
import ps.e7gz.admin.api.NewAdminAlert;
import ps.e7gz.booking.internal.StartTimeResolution.Outcome;
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

/**
 * The start-time cancellation (Spec v5 sections 31.3-31.4, FR-BOK-26, FR-BOK-28): CONFIRMED -> CANCELLED under the one
 * reason {@link StartTimeResolution} chose. One version of it for both callers - the BOOKING_START handler (T4, E7-60)
 * and the delayed-start no-show (E7-59, decisions of Amr Heiba 17 Oct 2026) - so a start-time cancellation is written
 * the same whichever reaches it first. Runs inside the caller's command, under the booking's Customer, Venue and
 * Resource locks; the caller has already withdrawn or is completing the booking's BOOKING_START job.
 *
 * <p>It is the on-time outcome, so every write of it is dated the stored {@code startAt} (arch 08 section 2.2): the
 * terminal instant, the reservation's release and the SYSTEM history row. The fee treatment is the reason map's
 * (FULL_REFUND for every start-time reason, section 31.3; {@code booking.expected_fee_treatment}); an Online Booking
 * records its Booking Fee decision, a Manual Booking has no customer and no fee. {@code BookingCancelled} carries the
 * reason to the one notification path (E7-55). The SEV_1 alert of {@code SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR}
 * commits with the cancellation (arch 12 section 6.2, E7-56), detected at the command instant. The Attendance Result
 * (NOT_APPLICABLE) is the caller's: Attendance owns it.
 */
@Component
class StartTimeCancellation {

    private static final Logger log = LoggerFactory.getLogger(StartTimeCancellation.class);

    private final VenueAnswerStore store;
    private final ReservationLedger ledger;
    private final BookingFeeRefunds refunds;
    private final Outbox outbox;
    private final AdminAlerts alerts;

    StartTimeCancellation(VenueAnswerStore store, ReservationLedger ledger, BookingFeeRefunds refunds, Outbox outbox,
                          AdminAlerts alerts) {
        this.store = store;
        this.ledger = ledger;
        this.refunds = refunds;
        this.outbox = outbox;
        this.alerts = alerts;
    }

    /** @throws ApiException {@code BOOKING_STATE_CHANGED} when the booking is no longer CONFIRMED */
    void cancel(CommandContext context, Facts booking, Outcome.Cancel cancel) {
        BookingTerminalReason reason = cancel.reason();
        Instant startAt = booking.startAt();
        ActiveReservation reservation = ledger.activeOf(context, ReservationOwner.booking(booking.bookingId()))
                .orElseThrow(() -> integrityFailure(booking.bookingId(),
                        "CONFIRMED booking holds no active reservation"));
        if (!store.terminate(context, booking.bookingId(), BookingStatus.CONFIRMED, BookingStatus.CANCELLED, reason,
                startAt, null)) {
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }
        ledger.release(context, reservation.id(), startAt, reason.name());
        UUID history = store.insertTransition(context, booking.bookingId(), startAt, BookingStatus.CONFIRMED,
                BookingStatus.CANCELLED, reason, ActorType.SYSTEM, null, CorrelationId.require());
        if (booking.source() == BookingSource.ONLINE) {
            refunds.recordDecision(context, booking.customerId(), booking.bookingId(), history);
            outbox.publish(context, BookingEvents.cancelled(booking, reason));
        } else {
            outbox.publish(context, BookingEvents.manualCancelled(booking, reason));
        }
        if (reason == BookingTerminalReason.SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR) {
            alerts.raise(context, NewAdminAlert.resourceStateIntegrityError(booking.bookingId(), booking.resourceId(),
                    cancel.unexpectedBaseState(), startAt, context.commandNow(), null, CorrelationId.require()));
        }
    }

    /**
     * D11 (E7-47): a booking past the start-time checks without its reservation, or with one that does not match it,
     * is a data defect - logged at ERROR, the command rolled back and one SEV_1 alert raised after the rollback (E7-56).
     */
    IllegalStateException integrityFailure(UUID bookingId, String detail) {
        log.error("Booking integrity failure: {} (bookingId={}, correlationId={})", detail, bookingId,
                CorrelationId.current().map(UUID::toString).orElse("none"));
        CorrelationId.current().ifPresent(correlation -> alerts.raiseAfterRollback(
                NewAdminAlert.bookingIntegrityFailure(bookingId, detail, correlation)));
        return new IllegalStateException("Booking " + bookingId + " integrity failure: " + detail);
    }
}
