package ps.e7gz.booking.internal;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.StartTimeAttendance;
import ps.e7gz.booking.internal.StartTimeResolution.Outcome;
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
import ps.e7gz.scheduling.api.NewJob;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.scheduling.api.ScheduledJobs;

/**
 * T4, the booking start (O11; arch 05 section 7, arch 08 section 1, FR-BOK-24 to FR-BOK-28, Spec v5 section 31): the
 * start-time revalidation and the CONFIRMED -> ACTIVE transition, or the start-time cancellation, in the one command
 * transaction of the deadline worker (section 31.1: atomic; a state changed before commit is re-read under the locks).
 * The rules are {@link StartTimeResolution}'s - the same the delayed-start no-show uses (E7-59) - never a second copy.
 *
 * <p>Decided from the booking and its stored {@code startAt}, never from when the job runs (arch 08 section 2.2,
 * NFR-14; Sprint 2 review 12 Oct 2026: overdue jobs on existing databases):
 * <ul>
 *   <li>no such booking, or no longer CONFIRMED - cancelled by the customer before the start, already started, a
 *       committed NO_SHOW behind a delayed start (never reactivated: E7-59), a start-time cancellation already written
 *       by the no-show - {@link JobOutcome#superseded}, nothing written;</li>
 *   <li>{@code commandNow < startAt} - {@link JobOutcome#rescheduleAt} {@code startAt} (FR-AVL-15);</li>
 *   <li>otherwise the resolution as of {@code startAt}: start, cancel under its one reason, or - undetermined - nothing
 *       written and the job fails (below).</li>
 * </ul>
 *
 * <p><b>Start</b>: CONFIRMED -> ACTIVE, the SYSTEM history row dated {@code startAt}, {@code BookingStarted}, and
 * BOOKING_COMPLETE scheduled at {@code endAt} for ONLINE and MANUAL (PO decision D14, 11 Oct 2026) - already due when
 * T4 runs after {@code endAt}, so it fires right after. The booking's own reservation must hold the booked interval;
 * anything else is a data defect (D11, rolled back with its SEV_1 alert). A Venue Account disable is not a start gate
 * (section 31.5): it never cancels a CONFIRMED booking.
 *
 * <p><b>Cancel</b>: {@link StartTimeCancellation}, then the Attendance Result NOT_APPLICABLE (section 31.3) through
 * {@link StartTimeAttendance}.
 *
 * <p><b>Undetermined</b>: nothing is written, no reason is guessed and nothing is started; the fire throws and the
 * worker backs the job off. Two cases, neither a Product state this task can resolve on its own:
 * <ul>
 *   <li>an orphaned selling block at {@code startAt} - no Product flow produces one (a block exists only for an
 *       unresolved Emergency, which the rungs above already cancel for), though the schema does not forbid it.
 *       Escalated as one SEV_1 booking integrity failure. Recovery - an authorised, audited correction of the data,
 *       then a safe scheduler recovery (E7-40 re-arm keeps the attempt count) - is tracked outside E7-60;</li>
 *   <li>for a late T4, a gate failing only because of a blocker that arose after {@code startAt} (Amr Heiba, 18 Oct
 *       2026: never a retroactive cancellation or penalty). The preferred outcome - the valid start, then the
 *       ACTIVE interruption at the blocker's own time (Spec v5 section 31.7) - needs the emergency and owner-review
 *       cascades of E7-82 and E7-84, which do not exist yet.</li>
 * </ul>
 */
@Component
class BookingStarts {

    private static final Logger log = LoggerFactory.getLogger(BookingStarts.class);

    private final VenueAnswerStore store;
    private final StartTimeResolution resolution;
    private final StartTimeCancellation cancellation;
    private final StartTimeAttendance attendance;
    private final ReservationLedger ledger;
    private final ScheduledJobs jobs;
    private final Outbox outbox;

    BookingStarts(VenueAnswerStore store, StartTimeResolution resolution, StartTimeCancellation cancellation,
                  StartTimeAttendance attendance, ReservationLedger ledger, ScheduledJobs jobs, Outbox outbox) {
        this.store = store;
        this.resolution = resolution;
        this.cancellation = cancellation;
        this.attendance = attendance;
        this.ledger = ledger;
        this.jobs = jobs;
        this.outbox = outbox;
    }

    JobOutcome start(CommandContext context, ScheduledJob job) {
        Optional<Facts> found = store.facts(context, job.subjectId());
        if (found.isEmpty()) {
            return JobOutcome.superseded("no such booking");
        }
        Facts booking = found.get();
        if (booking.status() != BookingStatus.CONFIRMED) {
            return JobOutcome.superseded("no longer CONFIRMED (" + booking.status() + ")");
        }
        if (context.commandNow().isBefore(booking.startAt())) {
            return JobOutcome.rescheduleAt(booking.startAt());
        }
        Outcome outcome = resolution.resolve(context, booking);
        switch (outcome) {
            case Outcome.Start start -> start(context, booking);
            case Outcome.Cancel cancel -> {
                cancellation.cancel(context, booking, cancel);
                attendance.resolveCancelledAtStart(context, booking.bookingId(), booking.source(),
                        context.commandNow());
            }
            case Outcome.Undetermined undetermined -> throw undetermined(booking, undetermined);
        }
        return JobOutcome.done();
    }

    /**
     * Nothing is written and the fire fails, so the worker backs the job off. An orphaned selling block is corrupted
     * state ({@link Outcome.Undetermined#isOrphanedBlock}; PO-delegated decision of Amr Heiba, 18 Oct 2026: an
     * integrity condition, escalated, never a Product operation): a booking integrity failure (D11, E7-47), one SEV_1
     * alert committed on its own once the command has rolled back. Every attempt asks for it and
     * {@link ps.e7gz.admin.api.AdminAlerts#raiseAfterRollback} keeps one: an open alert of the same kind, booking and
     * payload (the detail is the same on every attempt) is never repeated, and an attempt whose alert was lost - a
     * worker that died before its rollback, a raise that failed - is made good by the next one. Any other undetermined
     * start is a blocker that arose after startAt; its handling belongs to the ACTIVE interruption cascades (E7-82,
     * E7-84), so it is only logged here.
     */
    private RuntimeException undetermined(Facts booking, Outcome.Undetermined undetermined) {
        if (undetermined.isOrphanedBlock()) {
            return cancellation.integrityFailure(booking.bookingId(), undetermined.failingCheck()
                    + " in force at startAt with no unresolved Emergency (orphaned selling block, Spec v5 section 38.2)");
        }
        log.error("Start of booking {} not resolved, nothing written: {} (correlationId={})", booking.bookingId(),
                undetermined.why(), CorrelationId.current().map(UUID::toString).orElse("none"));
        return new IllegalStateException("Start of booking " + booking.bookingId() + " undetermined: "
                + undetermined.why());
    }

    /**
     * The start itself, CONFIRMED -> ACTIVE as of {@code startAt}, once the resolution said start: the one transition
     * T4 writes and a venue action behind a delayed start writes before its evidence (PO decision 19 Oct 2026, R1).
     * The caller has withdrawn or settled the start job.
     */
    void start(CommandContext context, Facts booking) {
        ownReservation(context, booking);
        if (!store.start(context, booking.bookingId())) {
            // re-read under the locks above: unreachable unless the guard moved it; roll back and let the job retry
            throw new IllegalStateException("Booking " + booking.bookingId() + " left CONFIRMED during its start");
        }
        store.insertTransition(context, booking.bookingId(), booking.startAt(), BookingStatus.CONFIRMED,
                BookingStatus.ACTIVE, null, ActorType.SYSTEM, null, CorrelationId.require());
        jobs.schedule(context, NewJob.of(ManualBookingCommand.BOOKING_COMPLETE,
                CreateOnlineBookingCommand.BOOKING_SUBJECT, booking.bookingId(), booking.endAt()));       // D14
        outbox.publish(context, booking.source() == BookingSource.MANUAL ? BookingEvents.manualStarted(booking)
                : BookingEvents.started(booking));
    }

    /** The booking's own active BOOKING reservation over exactly its Resource and interval (D11, E7-47). */
    private void ownReservation(CommandContext context, Facts booking) {
        ActiveReservation reservation = ledger.activeOf(context, ReservationOwner.booking(booking.bookingId()))
                .orElseThrow(() -> cancellation.integrityFailure(booking.bookingId(),
                        "CONFIRMED booking holds no active reservation"));
        if (!reservation.resourceId().equals(booking.resourceId())
                || !reservation.plannedStart().equals(booking.startAt())
                || !reservation.plannedEnd().equals(booking.endAt())) {
            throw cancellation.integrityFailure(booking.bookingId(),
                    "the reservation does not match the booking's Resource and interval");
        }
    }
}
