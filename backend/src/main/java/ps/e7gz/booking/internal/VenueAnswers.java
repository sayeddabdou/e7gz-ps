package ps.e7gz.booking.internal;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.internal.VenueAnswerStore.Facts;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.OperationalRestriction;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.identity.api.CustomerActivation;
import ps.e7gz.inventory.availability.api.AvailabilityCheck;
import ps.e7gz.inventory.availability.api.AvailabilityPeriod;
import ps.e7gz.inventory.availability.api.AvailabilityQuery;
import ps.e7gz.inventory.ledger.api.ActiveReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.loyalty.api.BookingFeeRefunds;
import ps.e7gz.messaging.api.CommandAnswer;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.operations.api.UnresolvedEmergencies;
import ps.e7gz.resource.api.ResourceStates;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.NewJob;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.trust.api.ActiveBans;
import ps.e7gz.venue.api.VenueEligibility;

/**
 * The venue's answer to an Online Booking and its response expiry (O2; arch 06 sections 1.2 and 2.1-2.2, FR-BOK-13 to
 * FR-BOK-18, FR-BOK-21; E7-47 with the PO decisions D1-D6 of 10 Oct 2026). Each method is a command body: it runs after
 * {@code begin_command} has locked the booking's Customer (with their Bookings, commitment guard and Bans), Venue and
 * Resource, re-reads the booking, decides from that state and {@link CommandContext#commandNow()}, and writes the
 * transition, its history row, the reservation release, the scheduled-job changes and the integration event together.
 * The database stays the authority on every transition; a booking that moved on is {@code BOOKING_STATE_CHANGED}.
 *
 * <p><b>Boundaries</b> (D5, FR-AVL-15): Accept and Reject need {@code now < venue_response_expires_at}, the stored
 * deadline; at or after it the booking still PENDING is {@code DEADLINE_PASSED} (expiry wins). Reject After Expiry
 * needs {@code now < startAt}. A paused response timer has no running deadline (D6, FR-OPS-34): it is never expired by
 * the clock alone.
 */
@Component
class VenueAnswers {

    private static final Logger log = LoggerFactory.getLogger(VenueAnswers.class);

    /** T4 (arch 08 section 1), scheduled by Accept; its handler belongs to the booking-start task. */
    static final String BOOKING_START = "BOOKING_START";

    private final VenueAnswerStore store;
    private final VenueBookings bookings;
    private final VenueEligibility venues;
    private final ResourceStates resources;
    private final UnresolvedEmergencies emergencies;
    private final AvailabilityQuery availability;
    private final ReservationLedger ledger;
    private final CustomerActivation activation;
    private final ActiveBans bans;
    private final ScheduledJobs jobs;
    private final Outbox outbox;
    private final BookingFeeRefunds refunds;

    VenueAnswers(VenueAnswerStore store, VenueBookings bookings, VenueEligibility venues, ResourceStates resources,
                 UnresolvedEmergencies emergencies, AvailabilityQuery availability, ReservationLedger ledger,
                 CustomerActivation activation, ActiveBans bans, ScheduledJobs jobs, Outbox outbox,
                 BookingFeeRefunds refunds) {
        this.store = store;
        this.bookings = bookings;
        this.venues = venues;
        this.resources = resources;
        this.emergencies = emergencies;
        this.availability = availability;
        this.ledger = ledger;
        this.activation = activation;
        this.bans = bans;
        this.jobs = jobs;
        this.outbox = outbox;
        this.refunds = refunds;
    }

    // ------------------------------------------------------------------------------------------------------ Accept

    /**
     * PENDING -> CONFIRMED (FR-BOK-14, FR-BOK-15), or - when an operational cause stops it - CANCELLED under that cause
     * with the matching 422 committed (FR-BOK-16, D2, D3).
     *
     * <p>Order: the booking's state; a paused timer (D6: refused, nothing changes); the deadline (D5); category B venue
     * eligibility - the account ENABLED, marketplace status not required (FR-BOK-15, ADR-022); the operational causes,
     * each identified explicitly in FR-BOK-23's priority; the canonical availability predicate for every other gate;
     * the booking's own reservation; and always the Customer's account and Ban (FR-BOK-14).
     */
    CommandAnswer<VenueBookingRow> accept(CommandContext context, UUID bookingId, UUID venueId, UUID venueAccountId) {
        Instant now = context.commandNow();
        Facts booking = pendingOf(context, bookingId, venueId);
        if (booking.responsePaused()) {
            throw pausedRefusal(context, booking, now);                                                 // D6
        }
        requireBeforeDeadline(booking, now);                                                            // D5

        VenueEligibility.Eligibility venue = venues.find(context, venueId)
                .orElseThrow(() -> new IllegalStateException("Venue " + venueId + " vanished"));
        if (venue.accountStatus() != VenueAccountStatus.ENABLED) {
            // An account disable moves PENDING to VENUE_RESPONSE_EXPIRED at once (FR-VEN-09); never a reason to cancel
            throw new ApiException(ApiErrorCode.FORBIDDEN);
        }

        Optional<OperationalCause> cause = operationalCause(context, booking, now);
        if (cause.isPresent()) {
            return CommandAnswer.refused(cancel(context, booking, cause.get(), now));                   // FR-BOK-16
        }

        // Every other gate of FR-BOK-14 through the one canonical predicate (arch 01 section 15 rule 1). The booking's
        // own active reservation always fails check 8, so check 8 failing means checks 1-7 passed; none of these
        // answers is an operational cause (D3), so each refuses without changing anything.
        AvailabilityCheck failing = availability.isFree(context, booking.resourceId(),
                        new AvailabilityPeriod(booking.startAt(), booking.endAt()), now).failingCheck()
                .orElseThrow(() -> integrityFailure(bookingId,
                        "PENDING booking has no active reservation on its slot"));
        switch (failing) {
            case BLOCKING_RESERVATION -> { }
            case VENUE_SELLING_BLOCK -> throw new ApiException(ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE);
            case RESOURCE_SELLING_BLOCK -> throw new ApiException(ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE);
            case RESOURCE_BASE_STATE, RESOURCE_OWNER_REVIEW, EMERGENCY_INTERVAL, MAINTENANCE, CLOSURE ->
                    throw new ApiException(ApiErrorCode.RESOURCE_NOT_ELIGIBLE);
        }
        ownReservation(context, booking);

        // Always the Customer's account and Ban (FR-BOK-14): refused, never cancelled, nothing stored (D3, D9). The one
        // neutral code never tells the venue why (banned, deleted or restricted).
        try {
            activation.requirePhoneVerified(context, booking.customerId());
        } catch (ApiException customerNotActive) {
            throw new ApiException(ApiErrorCode.CUSTOMER_NOT_ELIGIBLE);
        }
        if (bans.bannedAt(context, booking.customerId(), now)) {
            throw new ApiException(ApiErrorCode.CUSTOMER_NOT_ELIGIBLE);
        }

        if (!store.confirm(context, bookingId)) {
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }
        store.insertTransition(context, bookingId, now, BookingStatus.PENDING, BookingStatus.CONFIRMED, null,
                ActorType.VENUE, venueAccountId, CorrelationId.require());
        jobs.supersede(context, venueResponseExpiryKey(bookingId));                                     // D1
        jobs.schedule(context, NewJob.of(BOOKING_START, CreateOnlineBookingCommand.BOOKING_SUBJECT, bookingId,
                booking.startAt()));
        outbox.publish(context, BookingEvents.confirmed(booking));
        return CommandAnswer.ok(bookings.current(context, bookingId));
    }

    /**
     * The FR-BOK-16 branch: CANCELLED under the cause's reason, the reservation released now, T1 superseded, the
     * history row and {@code BookingCancelled}; the venue's refusal is the cause's 422. The actor is SYSTEM: the
     * operational state cancels, not the venue (no venue non-response blame).
     */
    private ApiException cancel(CommandContext context, Facts booking, OperationalCause cause, Instant now) {
        ActiveReservation reservation = ownReservation(context, booking);
        if (!store.terminate(context, booking.bookingId(), BookingStatus.PENDING, BookingStatus.CANCELLED,
                cause.reason(), now, null)) {
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }
        ledger.release(context, reservation.id(), now, cause.reason().name());
        UUID history = store.insertTransition(context, booking.bookingId(), now, BookingStatus.PENDING,
                BookingStatus.CANCELLED, cause.reason(), ActorType.SYSTEM, null, CorrelationId.require());
        refunds.recordDecision(context, booking.customerId(), booking.bookingId(), history);
        jobs.supersede(context, venueResponseExpiryKey(booking.bookingId()));
        outbox.publish(context, BookingEvents.cancelled(booking, cause.reason()));
        return new ApiException(cause.code());
    }

    /** FR-BOK-16's causes, each from its own state (D3), first in FR-BOK-23's priority; empty when none holds. */
    private Optional<OperationalCause> operationalCause(CommandContext context, Facts booking, Instant now) {
        ResourceStates.ResourceState resource = resources.find(context, booking.resourceId())
                .orElseThrow(() -> new IllegalStateException("Resource " + booking.resourceId() + " vanished"));
        if (resource.restriction() == OperationalRestriction.OWNER_REVIEW_REQUIRED) {
            return Optional.of(OperationalCause.OWNER_REVIEW);
        }
        if (emergencies.resourceEmergency(context, booking.resourceId(), now)) {
            return Optional.of(OperationalCause.RESOURCE_EMERGENCY);
        }
        if (emergencies.venueEmergency(context, booking.venueId(), now)) {
            return Optional.of(OperationalCause.VENUE_EMERGENCY);
        }
        return Optional.empty();
    }

    /**
     * D6, D7, D8: Accept on a paused response timer is an ordinary refusal - thrown, so nothing changes and nothing is
     * stored under the Idempotency-Key: a retry is evaluated afresh. RESOURCE_SELLING_BLOCK_ACTIVE for a resource-level
     * pause (an unresolved emergency of the booked Resource, FR-OPS-35), otherwise VENUE_SELLING_BLOCK_ACTIVE - a
     * venue-level pause, or one still waiting after its emergency ended for a valid intake window (FR-OPS-39), which is
     * the venue's own state (reported to the PO).
     */
    private ApiException pausedRefusal(CommandContext context, Facts booking, Instant now) {
        if (emergencies.resourceEmergency(context, booking.resourceId(), now)) {
            return new ApiException(ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE);
        }
        return new ApiException(ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE);
    }

    // ------------------------------------------------------------------------------------------------------ Reject

    /**
     * PENDING -> REJECTED / VENUE_REJECTED with the venue's mandatory reason text, kept apart from the code; the
     * reservation is released at once and T1 superseded (FR-BOK-17, D1). On a paused timer it is refused, changing
     * nothing: closing an open emergency pause belongs to Operations (FR-OPS-41; reported to the PO).
     */
    VenueBookingRow reject(CommandContext context, UUID bookingId, UUID venueId, UUID venueAccountId,
                           String reasonText) {
        Objects.requireNonNull(reasonText, "reasonText");
        Instant now = context.commandNow();
        Facts booking = pendingOf(context, bookingId, venueId);
        if (booking.responsePaused()) {
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }
        requireBeforeDeadline(booking, now);                                                            // D5
        ActiveReservation reservation = ownReservation(context, booking);
        BookingTerminalReason reason = BookingTerminalReason.VENUE_REJECTED;
        if (!store.terminate(context, bookingId, BookingStatus.PENDING, BookingStatus.REJECTED, reason, now,
                reasonText)) {
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }
        ledger.release(context, reservation.id(), now, reason.name());
        UUID history = store.insertTransition(context, bookingId, now, BookingStatus.PENDING, BookingStatus.REJECTED,
                reason, ActorType.VENUE, venueAccountId, CorrelationId.require());
        refunds.recordDecision(context, booking.customerId(), bookingId, history);
        jobs.supersede(context, venueResponseExpiryKey(bookingId));                                     // D1
        outbox.publish(context, BookingEvents.rejected(booking, reason));
        return bookings.current(context, bookingId);
    }

    /**
     * VENUE_RESPONSE_EXPIRED -> REJECTED / REJECTED_AFTER_EXPIRY, only while {@code now < startAt} (FR-BOK-21, D5).
     * Expiry already released the reservation, so there is none to release; one still active is an integrity defect.
     */
    VenueBookingRow rejectAfterExpiry(CommandContext context, UUID bookingId, UUID venueId, UUID venueAccountId) {
        Instant now = context.commandNow();
        Facts booking = ownedBy(context, bookingId, venueId);
        if (booking.source() != BookingSource.ONLINE || booking.status() != BookingStatus.VENUE_RESPONSE_EXPIRED) {
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }
        if (!now.isBefore(booking.startAt())) {
            throw new ApiException(ApiErrorCode.DEADLINE_PASSED);
        }
        if (ledger.activeOf(context, ReservationOwner.booking(bookingId)).isPresent()) {
            throw integrityFailure(bookingId, "VENUE_RESPONSE_EXPIRED booking holds a reservation");
        }
        BookingTerminalReason reason = BookingTerminalReason.REJECTED_AFTER_EXPIRY;
        if (!store.terminate(context, bookingId, BookingStatus.VENUE_RESPONSE_EXPIRED, BookingStatus.REJECTED, reason,
                now, null)) {
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }
        UUID history = store.insertTransition(context, bookingId, now, BookingStatus.VENUE_RESPONSE_EXPIRED,
                BookingStatus.REJECTED, reason, ActorType.VENUE, venueAccountId, CorrelationId.require());
        refunds.recordDecision(context, booking.customerId(), bookingId, history);
        outbox.publish(context, BookingEvents.rejected(booking, reason));
        return bookings.current(context, bookingId);
    }

    // ------------------------------------------------------------------------------------------------------ T1

    /**
     * T1 (arch 08 sections 2.2 and 2.5, FR-BOK-18): PENDING -> VENUE_RESPONSE_EXPIRED at the stored deadline. Decided
     * from the booking and the command instant, never from when the worker ran: no longer PENDING - superseded; before
     * the stored deadline (it moved later) - rescheduled to it; otherwise the effect as of the deadline - the history
     * row and the release are dated at {@code venue_response_expires_at} (D5). No terminal reason:
     * VENUE_RESPONSE_EXPIRED is non-terminal (FR-BOK-12).
     *
     * <p>A paused timer (D6, FR-OPS-34): the pause moves the job to PAUSED, and resume re-arms that same job with the
     * remaining time (FR-OPS-36). If the job fires while the booking is paused anyway, it throws - changing nothing -
     * so the job backs off and, if still paused, ends FAILED, which the resume can still re-arm; superseding it, as
     * the arch 08 section 2.2 sketch does, would leave nothing for the resume to re-arm (reported to the PO).
     */
    JobOutcome expire(CommandContext context, ScheduledJob job) {
        Optional<Facts> found = store.facts(context, job.subjectId());
        if (found.isEmpty()) {
            return JobOutcome.superseded("no such booking");
        }
        Facts booking = found.get();
        if (booking.source() != BookingSource.ONLINE || booking.status() != BookingStatus.PENDING) {
            return JobOutcome.superseded("no longer PENDING");
        }
        if (booking.responsePaused()) {
            throw new IllegalStateException("T1 fired for booking " + booking.bookingId()
                    + " while its response timer is paused");
        }
        Instant deadline = booking.venueResponseExpiresAt();
        if (context.commandNow().isBefore(deadline)) {
            return JobOutcome.rescheduleAt(deadline);
        }
        ActiveReservation reservation = ownReservation(context, booking);
        if (!store.expire(context, booking.bookingId())) {
            return JobOutcome.superseded("no longer PENDING");
        }
        ledger.release(context, reservation.id(), deadline, BookingStatus.VENUE_RESPONSE_EXPIRED.name());
        store.insertTransition(context, booking.bookingId(), deadline, BookingStatus.PENDING,
                BookingStatus.VENUE_RESPONSE_EXPIRED, null, ActorType.SYSTEM, null, CorrelationId.require());
        outbox.publish(context, BookingEvents.venueResponseExpired(booking));
        return JobOutcome.done();
    }

    // ------------------------------------------------------------------------------------------------------ shared

    static String venueResponseExpiryKey(UUID bookingId) {
        return NewJob.of(CreateOnlineBookingCommand.VENUE_RESPONSE_EXPIRY, CreateOnlineBookingCommand.BOOKING_SUBJECT,
                bookingId, Instant.EPOCH).idempotencyKey();
    }

    /** The booking of this venue; another venue's booking is not found (arch 10: no cross-venue access). */
    private Facts ownedBy(CommandContext context, UUID bookingId, UUID venueId) {
        return store.facts(context, bookingId)
                .filter(found -> found.venueId().equals(venueId))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }

    /** An Online Booking of this venue that is still PENDING (otherwise {@code BOOKING_STATE_CHANGED}, D5). */
    private Facts pendingOf(CommandContext context, UUID bookingId, UUID venueId) {
        Facts booking = ownedBy(context, bookingId, venueId);
        if (booking.source() != BookingSource.ONLINE || booking.status() != BookingStatus.PENDING) {
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }
        return booking;
    }

    /**
     * D11: a reservation or ledger invariant broken under a booking is a data defect, never a refusal the venue could
     * act on and never a cancellation (not SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR, which FR-BOK-28 ties to startAt).
     * Logged here at ERROR with the booking and correlation IDs; the thrown exception rolls the whole command back -
     * the Idempotency-Key record is never completed, so nothing is replayed - and the API answers a generic 500
     * without rule text. No Platform Admin alert: that path (E7-56) does not exist yet (reported to the PO).
     */
    private static IllegalStateException integrityFailure(UUID bookingId, String detail) {
        log.error("Booking integrity failure: {} (bookingId={}, correlationId={})", detail, bookingId,
                CorrelationId.current().map(UUID::toString).orElse("none"));
        return new IllegalStateException("Booking " + bookingId + " integrity failure: " + detail);
    }

    /** FR-AVL-15, D5: strictly before the stored deadline; at it, expiry wins. */
    private static void requireBeforeDeadline(Facts booking, Instant now) {
        if (!now.isBefore(booking.venueResponseExpiresAt())) {
            throw new ApiException(ApiErrorCode.DEADLINE_PASSED);
        }
    }

    /**
     * The PENDING booking's own active BOOKING reservation on exactly its Resource and interval; anything else is an
     * integrity failure (D3), never a refusal the venue could act on.
     */
    private ActiveReservation ownReservation(CommandContext context, Facts booking) {
        ActiveReservation reservation = ledger.activeOf(context, ReservationOwner.booking(booking.bookingId()))
                .orElseThrow(() -> integrityFailure(booking.bookingId(),
                        "PENDING booking holds no active reservation"));
        if (!reservation.resourceId().equals(booking.resourceId())
                || !reservation.plannedStart().equals(booking.startAt())
                || !reservation.plannedEnd().equals(booking.endAt())) {
            throw integrityFailure(booking.bookingId(),
                    "the reservation does not match the booking's Resource and interval");
        }
        return reservation;
    }
}
