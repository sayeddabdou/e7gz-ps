package ps.e7gz.booking.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ps.e7gz.admin.api.AdminAlerts;
import ps.e7gz.admin.api.NewAdminAlert;
import ps.e7gz.booking.api.AttendanceBookings;
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
import ps.e7gz.scheduling.api.ScheduledJobs;

/**
 * The delayed start behind a venue's no-show (decisions of Amr Heiba, acting decision-maker, 17 Oct 2026): a CONFIRMED
 * Online Booking past its {@code startAt} whose BOOKING_START (T4) is still waiting is resolved here as T4 would have
 * resolved it at {@code startAt} - by {@link StartTimeResolution}, the start-time rules E7-60 reuses, not a second
 * version of them. Runs inside the no-show's command, under the booking's Customer, Venue and Resource locks; see
 * {@link AttendanceBookings#resolveDelayedStart} for the four answers.
 *
 * <p>The start-time cancellation is the on-time outcome, so every write of it is dated the stored {@code startAt}: the
 * terminal instant, the reservation's release and the SYSTEM history row; the fee decision follows the reason map
 * (FULL_REFUND for every start-time reason, Spec v5 section 31.3) and {@code BookingCancelled} carries the reason to
 * the one notification path. The SEV_1 alert of {@code SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR} commits with it (arch 12
 * section 6.2, E7-56), detected at the command instant.
 */
@Component
class DelayedStartResolver {

    private static final Logger log = LoggerFactory.getLogger(DelayedStartResolver.class);

    private final VenueAnswerStore store;
    private final StartTimeResolution resolution;
    private final ScheduledJobs jobs;
    private final ReservationLedger ledger;
    private final BookingFeeRefunds refunds;
    private final Outbox outbox;
    private final AdminAlerts alerts;

    DelayedStartResolver(VenueAnswerStore store, StartTimeResolution resolution, ScheduledJobs jobs,
                         ReservationLedger ledger,
                         BookingFeeRefunds refunds, Outbox outbox, AdminAlerts alerts) {
        this.store = store;
        this.resolution = resolution;
        this.jobs = jobs;
        this.ledger = ledger;
        this.refunds = refunds;
        this.outbox = outbox;
        this.alerts = alerts;
    }

    AttendanceBookings.DelayedStart resolve(CommandContext context, UUID bookingId) {
        Optional<Facts> found = store.facts(context, bookingId);
        if (found.isEmpty() || found.get().source() != BookingSource.ONLINE
                || found.get().status() != BookingStatus.CONFIRMED
                || context.commandNow().isBefore(found.get().startAt())) {
            return AttendanceBookings.DelayedStart.NOT_DELAYED;
        }
        Facts booking = found.get();
        // Read-only first: an undetermined start writes nothing, not even the job's withdrawal
        Outcome outcome = resolution.resolve(context, booking);
        if (outcome instanceof Outcome.Undetermined undetermined) {
            log.warn("Delayed start of booking {} not resolved: {} (correlationId={})", bookingId, undetermined.why(),
                    CorrelationId.current().map(UUID::toString).orElse("none"));
            return AttendanceBookings.DelayedStart.UNDETERMINED;
        }
        // The proof of the race: the booking's own start job still waiting, withdrawn now so it can never fire
        if (!jobs.supersede(context, ManualBookingCommand.bookingStartKey(bookingId))) {
            return AttendanceBookings.DelayedStart.NOT_DELAYED;
        }
        return switch (outcome) {
            case Outcome.Start start -> AttendanceBookings.DelayedStart.WOULD_START;
            case Outcome.Cancel cancel -> {
                cancel(context, booking, cancel);
                yield AttendanceBookings.DelayedStart.CANCELLED;
            }
            case Outcome.Undetermined undetermined -> throw new IllegalStateException("handled above");
        };
    }

    private void cancel(CommandContext context, Facts booking, Outcome.Cancel cancel) {
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
        refunds.recordDecision(context, booking.customerId(), booking.bookingId(), history);
        outbox.publish(context, BookingEvents.cancelled(booking, reason));
        if (reason == BookingTerminalReason.SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR) {
            alerts.raise(context, NewAdminAlert.resourceStateIntegrityError(booking.bookingId(), booking.resourceId(),
                    cancel.unexpectedBaseState(), startAt, context.commandNow(), null, CorrelationId.require()));
        }
    }

    /** D11 (E7-47): a CONFIRMED booking without its reservation is a data defect - ERROR, rolled back, SEV_1 alert. */
    private IllegalStateException integrityFailure(UUID bookingId, String detail) {
        log.error("Booking integrity failure: {} (bookingId={}, correlationId={})", detail, bookingId,
                CorrelationId.current().map(UUID::toString).orElse("none"));
        CorrelationId.current().ifPresent(correlation -> alerts.raiseAfterRollback(
                NewAdminAlert.bookingIntegrityFailure(bookingId, detail, correlation)));
        return new IllegalStateException("Booking " + bookingId + " integrity failure: " + detail);
    }
}
