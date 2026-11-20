package ps.e7gz.booking.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.VenueAccountRestrictionsAt;
import ps.e7gz.booking.api.VenueResponseExpiredResolutions;
import ps.e7gz.booking.internal.VenueAnswerStore.Facts;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.loyalty.api.BookingFeeRefunds;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.NewJob;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.scheduling.api.ScheduledJobs;

/**
 * T6, {@code VENUE_RESPONSE_EXPIRED_RESOLUTION:{bookingId}} (arch 08 section 1; FR-BOK-23, Spec v5 section 24.3;
 * E7-118): a booking still VENUE_RESPONSE_EXPIRED at its original {@code startAt} becomes CANCELLED there and can no
 * longer be Late Confirmed.
 *
 * <p><b>Scheduled</b> at the stored {@code startAt} by every PENDING -> VENUE_RESPONSE_EXPIRED transition, in its own
 * command ({@link #scheduleAtStart}): T1 (E7-47) and, through {@link VenueResponseExpiredResolutions}, the Venue Account
 * disable (E7-78). <b>Withdrawn</b> in the command of every other exit from VENUE_RESPONSE_EXPIRED - a Late Confirm
 * initiation (E7-58) and Reject After Expiry (E7-47) ({@link #withdraw}); a fire that finds the booking gone anyway
 * supersedes itself.
 *
 * <p><b>Decided</b> from the booking and its stored {@code startAt}, never from when the job runs (arch 08 section 2.2,
 * NFR-14): before {@code startAt} the job is rescheduled to it (FR-AVL-15); at or after it the one reason is chosen by
 * FR-BOK-23's frozen priority, each cause read from its own state as of {@code startAt}:
 * <ol>
 *   <li>{@code RESOURCE_OWNER_REVIEW_REQUIRED}, 2. {@code RESOURCE_EMERGENCY_OUTAGE}, 3. {@code VENUE_EMERGENCY_CLOSURE}
 *       - E7-60's start-time reads ({@link StartTimeResolution#operationalCauseAtStart}), never a second copy;</li>
 *   <li value="4">{@code VENUE_ACCOUNT_DISABLED} - a restriction interval of the Venue in force at startAt
 *       ({@link VenueAccountRestrictionsAt});</li>
 *   <li>{@code VENUE_DID_NOT_RESPOND_IN_TIME} - only when none of the above held. A Venue Closure, an Opening Hours
 *       change or a DISABLED / ARCHIVED room is not a reason here (PO decisions D1, D3 of 19-20 Oct 2026: the frozen
 *       list wins; the database refuses any other reason on this transition).</li>
 * </ol>
 *
 * <p><b>Written</b> in the fire command, all dated {@code startAt}: VENUE_RESPONSE_EXPIRED -> CANCELLED with the one
 * reason and the database's fee treatment (FULL_REFUND for every reason of the list), the SYSTEM history row, the
 * Booking Fee decision, and one {@code BookingCancelled} - the customer's one generic BOOKING_AUTO_CANCELLED through
 * E7-55's consumer (PO decision D2). No reservation to release: the expiry already released it, and one still active
 * is an integrity defect (D11). No attendance row: the Attendance Result of a booking that never reached CONFIRMED is
 * NOT_APPLICABLE by default (section 24.3), as for a rejected or customer-cancelled request.
 */
@Component
class ExpiredRequestResolutions implements VenueResponseExpiredResolutions {

    /** T6's job type (arch 08 section 1). */
    static final String VENUE_RESPONSE_EXPIRED_RESOLUTION = "VENUE_RESPONSE_EXPIRED_RESOLUTION";

    private final VenueAnswerStore store;
    private final StartTimeResolution startTime;
    private final VenueAccountRestrictionsAt restrictions;
    private final ReservationLedger ledger;
    private final BookingFeeRefunds refunds;
    private final ScheduledJobs jobs;
    private final Outbox outbox;
    private final StartTimeCancellation integrity;

    ExpiredRequestResolutions(VenueAnswerStore store, StartTimeResolution startTime,
                              VenueAccountRestrictionsAt restrictions, ReservationLedger ledger,
                              BookingFeeRefunds refunds, ScheduledJobs jobs, Outbox outbox,
                              StartTimeCancellation integrity) {
        this.store = store;
        this.startTime = startTime;
        this.restrictions = restrictions;
        this.ledger = ledger;
        this.refunds = refunds;
        this.jobs = jobs;
        this.outbox = outbox;
        this.integrity = integrity;
    }

    static String resolutionKey(UUID bookingId) {
        return NewJob.of(VENUE_RESPONSE_EXPIRED_RESOLUTION, CreateOnlineBookingCommand.BOOKING_SUBJECT, bookingId,
                Instant.EPOCH).idempotencyKey();
    }

    @Override
    public void scheduleAtStart(CommandContext command, UUID bookingId) {
        Facts booking = store.facts(command, bookingId)
                .orElseThrow(() -> new IllegalStateException("No booking " + bookingId));
        if (booking.status() != BookingStatus.VENUE_RESPONSE_EXPIRED) {
            throw new IllegalStateException("Booking " + bookingId + " is " + booking.status()
                    + ", not VENUE_RESPONSE_EXPIRED");
        }
        jobs.schedule(command, NewJob.of(VENUE_RESPONSE_EXPIRED_RESOLUTION,
                CreateOnlineBookingCommand.BOOKING_SUBJECT, bookingId, booking.startAt()));
    }

    /** Withdraws the booking's T6 in the command that takes it out of VENUE_RESPONSE_EXPIRED. */
    void withdraw(CommandContext command, UUID bookingId) {
        jobs.supersede(command, resolutionKey(bookingId));
    }

    /** The fire of T6, in the deadline worker's command under the booking's Customer, Venue and Resource locks. */
    JobOutcome resolve(CommandContext context, ScheduledJob job) {
        Optional<Facts> found = store.facts(context, job.subjectId());
        if (found.isEmpty()) {
            return JobOutcome.superseded("no such booking");
        }
        Facts booking = found.get();
        if (booking.source() != BookingSource.ONLINE || booking.status() != BookingStatus.VENUE_RESPONSE_EXPIRED) {
            return JobOutcome.superseded("no longer VENUE_RESPONSE_EXPIRED (" + booking.status() + ")");
        }
        Instant startAt = booking.startAt();
        if (context.commandNow().isBefore(startAt)) {
            return JobOutcome.rescheduleAt(startAt);                                                 // FR-AVL-15
        }
        if (ledger.activeOf(context, ReservationOwner.booking(booking.bookingId())).isPresent()) {
            throw integrity.integrityFailure(booking.bookingId(),
                    "VENUE_RESPONSE_EXPIRED booking holds a reservation");
        }
        BookingTerminalReason reason = reasonAtStart(context, booking);
        if (!store.terminate(context, booking.bookingId(), BookingStatus.VENUE_RESPONSE_EXPIRED,
                BookingStatus.CANCELLED, reason, startAt, null)) {
            // re-read under the locks above: unreachable unless the guard moved it; roll back and let the job retry
            throw new IllegalStateException("Booking " + booking.bookingId()
                    + " left VENUE_RESPONSE_EXPIRED during its resolution");
        }
        UUID history = store.insertTransition(context, booking.bookingId(), startAt,
                BookingStatus.VENUE_RESPONSE_EXPIRED, BookingStatus.CANCELLED, reason, ActorType.SYSTEM, null,
                CorrelationId.require());
        refunds.recordDecision(context, booking.customerId(), booking.bookingId(), history);
        outbox.publish(context, BookingEvents.cancelled(booking, reason));
        return JobOutcome.done();
    }

    /** FR-BOK-23's frozen priority, every rung as of the stored {@code startAt}. */
    BookingTerminalReason reasonAtStart(CommandContext context, Facts booking) {
        return startTime.operationalCauseAtStart(context, booking)
                .orElseGet(() -> restrictions.disabledAt(context, booking.venueId(), booking.startAt())
                        ? BookingTerminalReason.VENUE_ACCOUNT_DISABLED
                        : BookingTerminalReason.VENUE_DID_NOT_RESPOND_IN_TIME);
    }
}
