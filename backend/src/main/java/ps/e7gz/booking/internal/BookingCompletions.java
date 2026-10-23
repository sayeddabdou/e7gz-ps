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
import ps.e7gz.booking.api.CompletionAttendance;
import ps.e7gz.booking.internal.VenueAnswerStore.Facts;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.inventory.ledger.api.ActiveReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;

/**
 * T5, the booking completion (O12; arch 07 section 7.3, arch 08 section 1, FR-ATT-07, FR-ATT-08): ACTIVE -> COMPLETED
 * with the booking's attendance default, in the one command transaction of the deadline worker. Scheduled at
 * {@code endAt} by every CONFIRMED -> ACTIVE start (E7-60, PO decision D14) and by a walk-in Manual Booking (E7-48, D8).
 *
 * <p>Decided from the booking and its stored {@code endAt}, never from when the job runs (arch 08 section 2.2, NFR-14;
 * Sprint 2 review 12 Oct 2026: walk-ins have waited PENDING since E7-48 and all fire when this handler deploys):
 * <ul>
 *   <li>no such booking, or no longer ACTIVE - a no-show (E7-59), a Manual Booking cancelled under PCR-007, any later
 *       ACTIVE interruption - {@link JobOutcome#superseded}, nothing written;</li>
 *   <li>{@code commandNow < endAt} - {@link JobOutcome#rescheduleAt} {@code endAt} (FR-AVL-15);</li>
 *   <li>otherwise the completion, as of {@code endAt}.</li>
 * </ul>
 *
 * <p><b>Completion</b>: the booking COMPLETED with {@code terminal_at = endAt} and the SYSTEM history row dated
 * {@code endAt}; its reservation released at {@code endAt}, its planned interval kept; {@code BookingCompleted}; then the
 * Attendance Result through {@link CompletionAttendance} - for an Online Booking with its Trust outcome and its Loyalty
 * reconciliation, for a Manual Booking NOT_APPLICABLE with neither (FR-MAN-06) - and {@code AttendanceResolved}. The
 * history row comes first: Loyalty dates the reward's configuration from it ({@code loyalty.booking_completion_anchor}).
 * The booking's own reservation must hold the booked interval and must release; anything else is a data defect (D11,
 * rolled back with its SEV_1 alert), so nothing of the completion - transition, events, attendance, Trust, Loyalty -
 * survives it.
 *
 * <p>The database's own guards stay the authority on the whole: a refusal rolls everything back and the worker backs the
 * job off, then marks it FAILED (E7-40). Until E7-72 builds the initial VERIFIED transition, that is the fate of a
 * customer's tenth attended completion ({@code trust.assert_user_integrity}; PO decisions 19 and 20 Oct 2026).
 */
@Component
class BookingCompletions {

    private static final Logger log = LoggerFactory.getLogger(BookingCompletions.class);

    private final VenueAnswerStore store;
    private final CompletionAttendance attendance;
    private final ReservationLedger ledger;
    private final Outbox outbox;
    private final AdminAlerts alerts;

    BookingCompletions(VenueAnswerStore store, CompletionAttendance attendance, ReservationLedger ledger,
                       Outbox outbox, AdminAlerts alerts) {
        this.store = store;
        this.attendance = attendance;
        this.ledger = ledger;
        this.outbox = outbox;
        this.alerts = alerts;
    }

    JobOutcome complete(CommandContext context, ScheduledJob job) {
        Optional<Facts> found = store.facts(context, job.subjectId());
        if (found.isEmpty()) {
            return JobOutcome.superseded("no such booking");
        }
        Facts booking = found.get();
        if (booking.status() != BookingStatus.ACTIVE) {
            return JobOutcome.superseded("no longer ACTIVE (" + booking.status() + ")");
        }
        if (context.commandNow().isBefore(booking.endAt())) {
            return JobOutcome.rescheduleAt(booking.endAt());
        }
        Instant endAt = booking.endAt();
        ActiveReservation reservation = ownReservation(context, booking);
        if (!store.complete(context, booking.bookingId(), endAt)) {
            // re-read under the locks above: unreachable unless the guard moved it; roll back and let the job retry
            throw new IllegalStateException("Booking " + booking.bookingId() + " left ACTIVE during its completion");
        }
        store.insertTransition(context, booking.bookingId(), endAt, BookingStatus.ACTIVE, BookingStatus.COMPLETED,
                null, ActorType.SYSTEM, null, CorrelationId.require());
        if (!ledger.release(context, reservation.id(), endAt, BookingStatus.COMPLETED.name())) {
            // read active under the locks above: a row that did not release is a data defect, never a completion
            throw integrityFailure(booking.bookingId(), "the booking's reservation was not released at completion");
        }
        outbox.publish(context, booking.source() == BookingSource.MANUAL ? BookingEvents.manualCompleted(booking)
                : BookingEvents.completed(booking));
        attendance.resolveAtCompletion(context, new AttendanceBookings.Booking(booking.bookingId(),
                booking.venueId(), booking.resourceId(), booking.customerId(), booking.source(),
                BookingStatus.COMPLETED, booking.startAt(), endAt));
        return JobOutcome.done();
    }

    /** The booking's own active BOOKING reservation over exactly its Resource and interval (D11, E7-47). */
    private ActiveReservation ownReservation(CommandContext context, Facts booking) {
        ActiveReservation reservation = ledger.activeOf(context, ReservationOwner.booking(booking.bookingId()))
                .orElseThrow(() -> integrityFailure(booking.bookingId(), "ACTIVE booking holds no active reservation"));
        if (!reservation.resourceId().equals(booking.resourceId())
                || !reservation.plannedStart().equals(booking.startAt())
                || !reservation.plannedEnd().equals(booking.endAt())) {
            throw integrityFailure(booking.bookingId(),
                    "the reservation does not match the booking's Resource and interval");
        }
        return reservation;
    }

    /**
     * D11 (E7-47): an ACTIVE booking without its reservation, with one that does not match it, or whose reservation does
     * not release, is a data defect - logged at ERROR, the command rolled back and one SEV_1 admin alert raised after
     * the rollback (E7-56).
     */
    private IllegalStateException integrityFailure(UUID bookingId, String detail) {
        log.error("Booking integrity failure: {} (bookingId={}, correlationId={})", detail, bookingId,
                CorrelationId.current().map(UUID::toString).orElse("none"));
        CorrelationId.current().ifPresent(correlation -> alerts.raiseAfterRollback(
                NewAdminAlert.bookingIntegrityFailure(bookingId, detail, correlation)));
        return new IllegalStateException("Booking " + bookingId + " integrity failure: " + detail);
    }
}
