package ps.e7gz.booking.lateconfirm.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.LateConfirmBookings;
import ps.e7gz.booking.api.LateConfirmBookings.Booking;
import ps.e7gz.booking.api.LateConfirmBookings.Transition;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmStore.Open;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmStore.Response;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingEventType;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.geography.api.AreaLookup;
import ps.e7gz.identity.api.CustomerActivation;
import ps.e7gz.inventory.availability.api.AvailabilityCheck;
import ps.e7gz.inventory.availability.api.AvailabilityPeriod;
import ps.e7gz.inventory.availability.api.AvailabilityQuery;
import ps.e7gz.inventory.ledger.api.ActiveReservation;
import ps.e7gz.inventory.ledger.api.NewReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.loyalty.api.BookingFeeRefunds;
import ps.e7gz.messaging.api.CommandAnswer;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.operations.api.ResponseTimerPauses;
import ps.e7gz.operations.api.UnresolvedEmergencies;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.trust.api.ActiveBans;
import ps.e7gz.venue.api.OperatingSessions;
import ps.e7gz.venue.api.SessionSnapshots;
import ps.e7gz.venue.api.VenueContacts;
import ps.e7gz.venue.api.VenueEligibility;

/**
 * O3 - Late Confirm initiate, accept and decline (arch 06 section 2.2, arch 03 section 12, FRS section 4.5; E7-58 with
 * the PO decisions D1-D4 of 12 Oct 2026). Each method is a command body: it runs after {@code begin_command} has locked
 * the booking's Customer (with their Bookings, commitment guard, Bans and Late Confirmations), Venue and Resource,
 * re-reads the booking and decides from that state and {@link CommandContext#commandNow()}. The transition, the hold,
 * the attempt, the history, the jobs and the event are written together, and the database checks the final state at
 * commit ({@code tr_late_confirmation_integrity} and its two companions).
 *
 * <p>Privacy (FR-LCF-05, FR-LCF-15, NFR-19, arch 10 section 5.2; D4): the venue learns only
 * {@code CUSTOMER_NOT_ELIGIBLE_FOR_LATE_CONFIRM} about the customer - never which rule, never their other bookings. The
 * customer's schedule is never checked at initiation, only on Accept (D1).
 */
@Component
class LateConfirmCommands {

    private static final Logger log = LoggerFactory.getLogger(LateConfirmCommands.class);

    /** FR-BOK-03, FR-LCF-04: at most 4 counted Online commitments. */
    static final int MAX_COMMITMENTS = 4;

    private static final BookingTerminalReason DECLINED = BookingTerminalReason.CUSTOMER_DECLINED_LATE_CONFIRM;
    private static final BookingTerminalReason TIMEOUT = BookingTerminalReason.CUSTOMER_RECONFIRM_TIMEOUT;

    private final LateConfirmBookings bookings;
    private final LateConfirmStore store;
    private final VenueEligibility venues;
    private final AreaLookup areas;
    private final OperatingSessions sessions;
    private final SessionSnapshots snapshots;
    private final AvailabilityQuery availability;
    private final UnresolvedEmergencies emergencies;
    private final CustomerActivation activation;
    private final ActiveBans bans;
    private final ReservationLedger ledger;
    private final ScheduledJobs jobs;
    private final Outbox outbox;
    private final BookingFeeRefunds refunds;
    private final ResponseTimerPauses pauses;
    private final VenueContacts contacts;

    LateConfirmCommands(LateConfirmBookings bookings, LateConfirmStore store, VenueEligibility venues, AreaLookup areas,
                        OperatingSessions sessions, SessionSnapshots snapshots, AvailabilityQuery availability,
                        UnresolvedEmergencies emergencies, CustomerActivation activation, ActiveBans bans,
                        ReservationLedger ledger, ScheduledJobs jobs, Outbox outbox, BookingFeeRefunds refunds,
                        ResponseTimerPauses pauses, VenueContacts contacts) {
        this.bookings = bookings;
        this.store = store;
        this.venues = venues;
        this.areas = areas;
        this.sessions = sessions;
        this.snapshots = snapshots;
        this.availability = availability;
        this.emergencies = emergencies;
        this.activation = activation;
        this.bans = bans;
        this.ledger = ledger;
        this.jobs = jobs;
        this.outbox = outbox;
        this.refunds = refunds;
        this.pauses = pauses;
        this.contacts = contacts;
    }

    // ------------------------------------------------------------------------------------------------------ Initiate

    /**
     * VENUE_RESPONSE_EXPIRED -> AWAITING_CUSTOMER_CONFIRMATION, one atomic transaction (FR-LCF-06): the venue, the
     * room, the customer, then the hold, the transition, the attempt, T2 (and T3 for a window over 60 s), the history
     * row and {@code LateConfirmInitiated}.
     *
     * <p>Order: the booking (another venue's is NOT_FOUND; not an expired Online Booking is BOOKING_STATE_CHANGED; at
     * or after its start DEADLINE_PASSED); the venue (FR-LCF-02: account ENABLED and not ONBOARDING, else FORBIDDEN;
     * area ACTIVE, else AREA_INACTIVE; inside an operating session now and the interval still within one, else
     * INTERVAL_NOT_WITHIN_ONE_SESSION - PO decision 11 Oct 2026, E7-54); the room through the one canonical predicate
     * (FR-LCF-03: a selling block or the room's state is a plain refusal; a taken interval records
     * {@code LATE_CONFIRM_FAILED_RESOURCE_CONFLICT} and answers the stored 409 LATE_CONFIRM_RESOURCE_CONFLICT, Q2);
     * the customer last (FR-LCF-04), so the limit event is recorded only for an initiation that would
     * otherwise succeed. Not restricted by the intake window (FR-LCF-07).
     *
     * <p>D4: a deleted, unverified or banned customer is the neutral 422 and nothing is written. At 4 commitments the
     * {@code LATE_CONFIRM_FAILED_BOOKING_LIMIT} event commits with the same neutral 422 stored as the answer: no hold,
     * the booking stays VENUE_RESPONSE_EXPIRED (FR-LCF-04, FR-LCF-05). The booking being revived holds no commitment
     * while expired, so 4 existing commitments make it the fifth.
     */
    CommandAnswer<SentLateConfirmation> initiate(CommandContext context, UUID bookingId, UUID venueId,
                                                 UUID venueAccountId) {
        Instant now = context.commandNow();
        Booking booking = bookings.find(context, bookingId)
                .filter(found -> found.venueId().equals(venueId))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        if (booking.source() != BookingSource.ONLINE || booking.status() != BookingStatus.VENUE_RESPONSE_EXPIRED) {
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }
        if (!now.isBefore(booking.startAt())) {
            throw new ApiException(ApiErrorCode.DEADLINE_PASSED);                                // FR-LCF-02
        }

        VenueEligibility.Eligibility venue = venues.find(context, venueId)
                .orElseThrow(() -> new IllegalStateException("Venue " + venueId + " vanished"));
        if (venue.accountStatus() != VenueAccountStatus.ENABLED
                || venue.marketplaceStatus() == MarketplaceStatus.ONBOARDING) {
            throw new ApiException(ApiErrorCode.FORBIDDEN);                                      // FR-LCF-02, D4
        }
        if (!areas.isActive(context, venue.areaId())) {
            throw new ApiException(ApiErrorCode.AREA_INACTIVE);
        }
        boolean insideSessionNow = sessions.endingAfter(context, venueId, now).stream()
                .anyMatch(session -> !session.start().isAfter(now));
        if (!insideSessionNow || !intervalWithinOneSession(context, booking)) {
            throw new ApiException(ApiErrorCode.INTERVAL_NOT_WITHIN_ONE_SESSION);
        }
        Optional<AvailabilityCheck> failing = availability.isFree(context, booking.resourceId(), period(booking), now)
                .failingCheck();                                                                 // FR-LCF-03
        if (failing.isPresent()) {
            if (resourceConflict(failing.get())) {
                bookings.recordEvent(context, bookingId, now, BookingEventType.LATE_CONFIRM_FAILED_RESOURCE_CONFLICT,
                        ActorType.VENUE, venueAccountId);                                        // FR-LCF-17, Q2
                return CommandAnswer.refused(new ApiException(ApiErrorCode.LATE_CONFIRM_RESOURCE_CONFLICT));
            }
            throw roomRefusal(failing.get());
        }

        UUID customerId = booking.customerId();
        if (!customerActive(context, customerId) || bans.bannedAt(context, customerId, now)) {
            throw notEligible();                                                                 // D4: no event
        }
        if (bookings.countCommitments(context, customerId) >= MAX_COMMITMENTS) {
            bookings.recordEvent(context, bookingId, now, BookingEventType.LATE_CONFIRM_FAILED_BOOKING_LIMIT,
                    ActorType.VENUE, venueAccountId);                                            // FR-LCF-05
            return CommandAnswer.refused(notEligible());
        }

        UUID hold = ledger.create(context, new NewReservation(booking.resourceId(),
                ReservationOwner.lateConfirmHold(bookingId), booking.startAt(), booking.endAt()));  // FR-LCF-08
        if (!bookings.awaitCustomer(context, bookingId)) {
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }
        LateConfirmWindow window = LateConfirmWindow.of(now, booking.startAt());                  // FR-LCF-09
        store.insert(context, bookingId, now, window.expiresAt(), hold);
        bookings.recordTransition(context, new Transition(bookingId, now, BookingStatus.VENUE_RESPONSE_EXPIRED,
                BookingStatus.AWAITING_CUSTOMER_CONFIRMATION, null, ActorType.VENUE, venueAccountId,
                CorrelationId.require()));
        jobs.schedule(context, window.expiryJob(bookingId));                                      // T2
        window.warningJob(bookingId).ifPresent(warning -> jobs.schedule(context, warning));       // T3, FR-LCF-12
        outbox.publish(context, LateConfirmEvents.initiated(booking, venueName(context, booking),
                window.expiresAt()));
        return CommandAnswer.ok(new SentLateConfirmation(bookingId, BookingStatus.AWAITING_CUSTOMER_CONFIRMATION,
                window.expiresAt()));
    }

    // ------------------------------------------------------------------------------------------------------ Accept

    /**
     * AWAITING_CUSTOMER_CONFIRMATION -> CONFIRMED, same Booking ID, price and fee (FR-LCF-08, FR-LCF-13), revalidated
     * atomically (FR-LCF-14).
     *
     * <p>Order: the booking (another customer's is NOT_FOUND; not AWAITING is BOOKING_STATE_CHANGED); a paused window
     * (FR-OPS-41 allows only Decline: VENUE_SELLING_BLOCK_ACTIVE, or RESOURCE_SELLING_BLOCK_ACTIVE under a resource
     * emergency - thrown, nothing changes, as E7-47 D8); the stored deadline (DEADLINE_PASSED at or after it); the
     * customer's account, Ban and commitment count; the venue and the room (area, the interval within one session, the
     * canonical predicate - the booking's own hold is the one blocking reservation); then the customer's schedule (D1).
     *
     * <p>Two failures commit their history event and the booking stays AWAITING with its hold and deadline (FR-LCF-16,
     * FR-LCF-17): another counted commitment of the customer overlapping the interval - checked here, under the
     * commitment guard, never left to {@code ex_customer_no_overlap}, which exempts AWAITING (D1); the customer's
     * other AWAITING bookings are no conflict, so the first of two overlapping Accepts wins (Q1, FR-LCF-10) - records
     * {@code LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT} and answers 409 LATE_CONFIRM_CUSTOMER_CONFLICT; a taken interval
     * (an emergency interval, Maintenance or a Closure - the hold keeps every other booking out) records
     * {@code LATE_CONFIRM_FAILED_RESOURCE_CONFLICT} and answers 409 LATE_CONFIRM_RESOURCE_CONFLICT (Q2).
     * Every other refusal is thrown and changes nothing.
     *
     * <p>Success: the hold is released and the canonical BOOKING reservation inserted at the command instant (in that
     * order: they cover the same interval), the attempt closed ACCEPTED, T2 and T3 superseded, T4 scheduled and
     * {@code LateConfirmAccepted} written.
     *
     * <p>KNOWN GAP until E7-78 (Q3, 13 Oct 2026; reported to the PO): the venue account status is not checked here.
     * FR-VEN-09 cancels an AWAITING booking under VENUE_ACCOUNT_DISABLED when the account is disabled, but that cascade
     * is E7-78's and does not exist yet, so today a disabled venue's open Late Confirmation can still be accepted. No
     * declared Accept code fits a refusal (E7-54), so none is invented. E7-78 closes it: the disable and this command
     * lock the same Venue, so an Accept either commits before the disable (CONFIRMED is unaffected) or finds the
     * booking CANCELLED (BOOKING_STATE_CHANGED).
     */
    CommandAnswer<LateConfirmAnswer> accept(CommandContext context, UUID bookingId, UUID customerId) {
        Instant now = context.commandNow();
        Booking booking = customersAwaiting(context, bookingId, customerId);
        Open attempt = openAttempt(context, booking);
        if (booking.lateConfirmPaused()) {
            throw pausedRefusal(context, booking, now);                                          // FR-OPS-41, D3
        }
        requireBeforeDeadline(attempt, now);

        activation.requirePhoneVerified(context, customerId);                                    // ACCOUNT_DELETED
        if (bans.bannedAt(context, customerId, now)) {
            throw new ApiException(ApiErrorCode.ACCOUNT_BANNED_FOR_THIS_ACTION);                // FR-ACC-17
        }
        // The booking's own AWAITING commitment is one of them: more than 4 can only follow a defect elsewhere
        if (bookings.countCommitments(context, customerId) > MAX_COMMITMENTS) {
            throw new ApiException(ApiErrorCode.COMMITMENT_LIMIT_REACHED);
        }

        VenueEligibility.Eligibility venue = venues.find(context, booking.venueId())
                .orElseThrow(() -> new IllegalStateException("Venue " + booking.venueId() + " vanished"));
        if (!areas.isActive(context, venue.areaId())) {
            throw new ApiException(ApiErrorCode.AREA_INACTIVE);
        }
        if (!intervalWithinOneSession(context, booking)) {
            throw new ApiException(ApiErrorCode.INTERVAL_NOT_WITHIN_ONE_SESSION);
        }
        ActiveReservation hold = ownHold(context, booking, attempt);
        AvailabilityCheck failing = availability.isFree(context, booking.resourceId(), period(booking), now)
                .failingCheck()
                .orElseThrow(() -> integrityFailure(bookingId, "the AWAITING booking's hold does not block its slot"));
        if (failing != AvailabilityCheck.BLOCKING_RESERVATION) {
            if (resourceConflict(failing)) {
                bookings.recordEvent(context, bookingId, now, BookingEventType.LATE_CONFIRM_FAILED_RESOURCE_CONFLICT,
                        ActorType.CUSTOMER, customerId);                                         // FR-LCF-17, Q2
                return CommandAnswer.refused(new ApiException(ApiErrorCode.LATE_CONFIRM_RESOURCE_CONFLICT));
            }
            throw roomRefusal(failing);
        }

        if (bookings.overlapsAnotherCommitment(context, customerId, bookingId, booking.startAt(), booking.endAt())) {
            bookings.recordEvent(context, bookingId, now, BookingEventType.LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT,
                    ActorType.CUSTOMER, customerId);                                             // FR-LCF-16, D1
            return CommandAnswer.refused(new ApiException(ApiErrorCode.LATE_CONFIRM_CUSTOMER_CONFLICT));
        }

        ledger.release(context, hold.id(), now, Response.ACCEPTED.name());
        ledger.create(context, new NewReservation(booking.resourceId(), ReservationOwner.booking(bookingId),
                booking.startAt(), booking.endAt()));
        if (!bookings.confirm(context, bookingId)) {
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }
        respond(context, attempt, Response.ACCEPTED, now);
        bookings.recordTransition(context, new Transition(bookingId, now,
                BookingStatus.AWAITING_CUSTOMER_CONFIRMATION, BookingStatus.CONFIRMED, null, ActorType.CUSTOMER,
                customerId, CorrelationId.require()));
        supersedeJobs(context, bookingId);
        bookings.scheduleStart(context, bookingId, booking.startAt());                          // T4
        outbox.publish(context, LateConfirmEvents.accepted(booking, venueName(context, booking)));
        return CommandAnswer.ok(new LateConfirmAnswer(bookingId, BookingStatus.CONFIRMED, null));
    }

    // ------------------------------------------------------------------------------------------------------ Decline

    /**
     * AWAITING_CUSTOMER_CONFIRMATION -> CANCELLED / CUSTOMER_DECLINED_LATE_CONFIRM, without penalty (FR-LCF-13): the
     * hold released, the attempt closed DECLINED, the history row, the Booking Fee decision (a full refund: Late
     * Confirm exists only through the venue's own expiry, FR-PTS-17), T2 and T3 superseded and
     * {@code LateConfirmDeclined}. No Trust event is written.
     *
     * <p>D3: never refused for a paused window or an emergency (FR-OPS-41): a pause ends with the Decline - added to
     * the cumulative Late Confirm pause and its open history row closed. It needs {@code now < expires_at}, the stored
     * deadline ({@code ck_late_confirmation_response_shape}); keeping that deadline valid during a pause is E7-83's. A
     * banned customer may still decline: no account or Ban check.
     */
    LateConfirmAnswer decline(CommandContext context, UUID bookingId, UUID customerId) {
        Instant now = context.commandNow();
        Booking booking = customersAwaiting(context, bookingId, customerId);
        Open attempt = openAttempt(context, booking);
        requireBeforeDeadline(attempt, now);

        if (booking.lateConfirmPaused()) {
            Duration accumulated = bookings.endLateConfirmPause(context, bookingId, now)
                    .orElseThrow(() -> new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED));
            pauses.closeOpenLateConfirm(context, bookingId, now, accumulated);
        }
        ActiveReservation hold = ownHold(context, booking, attempt);
        if (!bookings.decline(context, bookingId, now)) {
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }
        ledger.release(context, hold.id(), now, DECLINED.name());
        respond(context, attempt, Response.DECLINED, now);
        UUID history = bookings.recordTransition(context, new Transition(bookingId, now,
                BookingStatus.AWAITING_CUSTOMER_CONFIRMATION, BookingStatus.CANCELLED, DECLINED, ActorType.CUSTOMER,
                customerId, CorrelationId.require()));
        refunds.recordDecision(context, customerId, bookingId, history);
        supersedeJobs(context, bookingId);
        outbox.publish(context, LateConfirmEvents.declined(booking, venueName(context, booking)));
        return new LateConfirmAnswer(bookingId, BookingStatus.CANCELLED, DECLINED);
    }

    // ------------------------------------------------------------------------------------------------------ T2

    /**
     * T2, {@code LATE_CONFIRM_EXPIRY} (arch 08 sections 1 and 2.2, FR-LCF-09, FR-LCF-13):
     * AWAITING_CUSTOMER_CONFIRMATION -> CANCELLED / CUSTOMER_RECONFIRM_TIMEOUT at the stored deadline, without penalty.
     * Decided from the booking, its open attempt and the command instant, never from when the worker ran:
     * <ul>
     *   <li>no such booking, or no longer AWAITING - superseded (an Accept or Decline won);</li>
     *   <li>AWAITING with no open attempt - an integrity failure (as E7-47 D11): ERROR, the command rolls back and the
     *       worker's bounded retries end it FAILED, never a silent supersede;</li>
     *   <li>a paused window (FR-OPS-35): thrown, changing nothing, as T1 does (E7-47 D13) - the job backs off and, if
     *       still paused, ends FAILED; re-arming it with the remaining time is E7-83's (PO decision 12 Oct 2026);</li>
     *   <li>before the stored deadline (it moved later) - rescheduled to it;</li>
     *   <li>otherwise the effect as of the deadline: {@code terminal_at}, the hold's release, the attempt's
     *       {@code responded_at}/{@code closed_at} (TIMED_OUT) and the history row are all the stored
     *       {@code expires_at}, so a late fire never moves the logical timeout (FR-AVL-15). The Booking Fee decision is
     *       recorded (a full refund: FR-PTS-17), T3 superseded and {@code LateConfirmTimedOut} written. No Trust
     *       event.</li>
     * </ul>
     */
    JobOutcome expire(CommandContext context, ScheduledJob job) {
        Optional<Booking> found = bookings.find(context, job.subjectId());
        if (found.isEmpty()) {
            return JobOutcome.superseded("no such booking");
        }
        Booking booking = found.get();
        if (booking.source() != BookingSource.ONLINE
                || booking.status() != BookingStatus.AWAITING_CUSTOMER_CONFIRMATION) {
            return JobOutcome.superseded("no longer AWAITING_CUSTOMER_CONFIRMATION");
        }
        Open attempt = openAttempt(context, booking);                                           // R1: a defect
        if (booking.lateConfirmPaused()) {
            throw new IllegalStateException("T2 fired for booking " + booking.bookingId()
                    + " while its Late Confirm window is paused");
        }
        Instant deadline = attempt.expiresAt();
        if (context.commandNow().isBefore(deadline)) {
            return JobOutcome.rescheduleAt(deadline);
        }
        ActiveReservation hold = ownHold(context, booking, attempt);
        if (!bookings.timeOut(context, booking.bookingId(), deadline)) {
            return JobOutcome.superseded("no longer AWAITING_CUSTOMER_CONFIRMATION");
        }
        ledger.release(context, hold.id(), deadline, TIMEOUT.name());
        respond(context, attempt, Response.TIMED_OUT, deadline);
        UUID history = bookings.recordTransition(context, new Transition(booking.bookingId(), deadline,
                BookingStatus.AWAITING_CUSTOMER_CONFIRMATION, BookingStatus.CANCELLED, TIMEOUT, ActorType.SYSTEM,
                null, CorrelationId.require()));
        refunds.recordDecision(context, booking.customerId(), booking.bookingId(), history);
        jobs.supersede(context, LateConfirmWindow.warningKey(booking.bookingId()));
        outbox.publish(context, LateConfirmEvents.timedOut(booking, venueName(context, booking), deadline));
        return JobOutcome.done();
    }

    // ------------------------------------------------------------------------------------------------------ T3

    /**
     * T3, {@code LATE_CONFIRM_WARNING} (arch 08 section 2.6, FR-LCF-12): {@code LateConfirmWarningDue} about a minute
     * before the deadline, once per attempt. Initiate writes the job only for a window longer than 60 seconds; this
     * never creates one. Decided from the open attempt and the command instant:
     * <ul>
     *   <li>no longer AWAITING, or already warned - superseded (nothing is sent twice);</li>
     *   <li>AWAITING with no open attempt - an integrity failure, as T2;</li>
     *   <li>a paused window: thrown, changing nothing, as T2;</li>
     *   <li>before {@code expires_at - 60 s} (the deadline moved later) - rescheduled to it;</li>
     *   <li>at or after {@code expires_at} - superseded: the window is over, T2 owns the booking, no warning;</li>
     *   <li>otherwise {@code warning_sent_at} is set to the command instant - only while it is still NULL, so a retry
     *       or a second worker changes nothing - and the event is written in the same transaction.</li>
     * </ul>
     */
    JobOutcome warn(CommandContext context, ScheduledJob job) {
        Instant now = context.commandNow();
        Optional<Booking> found = bookings.find(context, job.subjectId());
        if (found.isEmpty()) {
            return JobOutcome.superseded("no such booking");
        }
        Booking booking = found.get();
        if (booking.source() != BookingSource.ONLINE
                || booking.status() != BookingStatus.AWAITING_CUSTOMER_CONFIRMATION) {
            return JobOutcome.superseded("no longer AWAITING_CUSTOMER_CONFIRMATION");
        }
        Open attempt = openAttempt(context, booking);                                           // R1: a defect
        if (attempt.warningSentAt() != null) {
            return JobOutcome.superseded("already warned");
        }
        if (booking.lateConfirmPaused()) {
            throw new IllegalStateException("T3 fired for booking " + booking.bookingId()
                    + " while its Late Confirm window is paused");
        }
        Instant warningAt = attempt.expiresAt().minus(LateConfirmWindow.WARNING_LEAD);
        if (now.isBefore(warningAt)) {
            return JobOutcome.rescheduleAt(warningAt);
        }
        if (!now.isBefore(attempt.expiresAt())) {
            return JobOutcome.superseded("the Late Confirmation expired before its warning");
        }
        if (!store.markWarned(context, attempt.id(), now)) {
            return JobOutcome.superseded("already warned");
        }
        outbox.publish(context, LateConfirmEvents.warningDue(booking, venueName(context, booking),
                attempt.expiresAt()));
        return JobOutcome.done();
    }

    // ------------------------------------------------------------------------------------------------------ shared

    /** Q4: the venue's display name at the event, for the notification copy (FR-LCF-11). */
    private String venueName(CommandContext context, Booking booking) {
        return contacts.find(context, booking.venueId())
                .orElseThrow(() -> new IllegalStateException("Venue " + booking.venueId() + " vanished"))
                .name();
    }

    /** The customer's own Online Booking (another's, or a Manual one, is NOT_FOUND), still AWAITING. */
    private Booking customersAwaiting(CommandContext context, UUID bookingId, UUID customerId) {
        Objects.requireNonNull(customerId, "customerId");
        Booking booking = bookings.find(context, bookingId)
                .filter(found -> found.source() == BookingSource.ONLINE && customerId.equals(found.customerId()))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        if (booking.status() != BookingStatus.AWAITING_CUSTOMER_CONFIRMATION) {
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }
        return booking;
    }

    /** An AWAITING booking always has its open attempt (tr_lc_integrity_from_booking); none is a data defect. */
    private Open openAttempt(CommandContext context, Booking booking) {
        return store.open(context, booking.bookingId())
                .orElseThrow(() -> integrityFailure(booking.bookingId(),
                        "AWAITING booking has no open Late Confirmation"));
    }

    /** The attempt's own active hold on exactly the booking's Resource and interval; anything else is a defect. */
    private ActiveReservation ownHold(CommandContext context, Booking booking, Open attempt) {
        ActiveReservation hold = ledger.activeOf(context, ReservationOwner.lateConfirmHold(booking.bookingId()))
                .orElseThrow(() -> integrityFailure(booking.bookingId(), "AWAITING booking holds no active hold"));
        if (!hold.id().equals(attempt.holdReservationId())
                || !hold.resourceId().equals(booking.resourceId())
                || !hold.plannedStart().equals(booking.startAt())
                || !hold.plannedEnd().equals(booking.endAt())) {
            throw integrityFailure(booking.bookingId(), "the hold does not match the Late Confirmation");
        }
        return hold;
    }

    private void respond(CommandContext context, Open attempt, Response response, Instant now) {
        if (!store.respond(context, attempt.id(), response, now)) {
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }
    }

    /** T2 and T3 (a missing T3 - a window of 60 s or less - is no change); a LEASED job re-reads the booking itself. */
    private void supersedeJobs(CommandContext context, UUID bookingId) {
        jobs.supersede(context, LateConfirmWindow.expiryKey(bookingId));
        jobs.supersede(context, LateConfirmWindow.warningKey(bookingId));
    }

    /** FR-LCF-02, FR-LCF-14: the original interval still lies within one valid operating session (opening hours). */
    private boolean intervalWithinOneSession(CommandContext context, Booking booking) {
        return snapshots.containing(context, booking.venueId(), booking.startAt())
                .filter(session -> !session.end().isBefore(booking.endAt()))
                .isPresent();
    }

    /**
     * FR-LCF-17 (E7-58 Q2, 13 Oct 2026): the interval itself is taken - a declared emergency interval, Maintenance, a
     * Closure or another active commitment (checks 5-8). A resource conflict records
     * {@code LATE_CONFIRM_FAILED_RESOURCE_CONFLICT} and answers 409 LATE_CONFIRM_RESOURCE_CONFLICT; a selling block or
     * the room's own state (checks 1-4) is an ordinary validation refusal that records nothing.
     */
    private static boolean resourceConflict(AvailabilityCheck failing) {
        return switch (failing) {
            case EMERGENCY_INTERVAL, MAINTENANCE, CLOSURE, BLOCKING_RESERVATION -> true;
            case VENUE_SELLING_BLOCK, RESOURCE_SELLING_BLOCK, RESOURCE_BASE_STATE, RESOURCE_OWNER_REVIEW -> false;
        };
    }

    /** The validation answers of the canonical predicate, checks 1-4 (FR-LCF-03; PO decision 11 Oct 2026, E7-54). */
    private static ApiException roomRefusal(AvailabilityCheck failing) {
        return new ApiException(switch (failing) {
            case VENUE_SELLING_BLOCK -> ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE;
            case RESOURCE_SELLING_BLOCK -> ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE;
            case RESOURCE_BASE_STATE, RESOURCE_OWNER_REVIEW -> ApiErrorCode.RESOURCE_NOT_ELIGIBLE;
            case EMERGENCY_INTERVAL, MAINTENANCE, CLOSURE, BLOCKING_RESERVATION ->
                    throw new IllegalArgumentException(failing + " is a resource conflict, not a refusal");
        });
    }

    /**
     * FR-OPS-41, D3: Accept on a paused window is an ordinary refusal - thrown, so nothing changes and nothing is
     * stored under the Idempotency-Key (as E7-47 D8). RESOURCE_SELLING_BLOCK_ACTIVE under an unresolved emergency of
     * the booked Resource (FR-OPS-35), otherwise VENUE_SELLING_BLOCK_ACTIVE.
     */
    private ApiException pausedRefusal(CommandContext context, Booking booking, Instant now) {
        if (emergencies.resourceEmergency(context, booking.resourceId(), now)) {
            return new ApiException(ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE);
        }
        return new ApiException(ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE);
    }

    /** FR-AVL-15: strictly before the stored deadline; at it, T2 owns the state. */
    private static void requireBeforeDeadline(Open attempt, Instant now) {
        if (!now.isBefore(attempt.expiresAt())) {
            throw new ApiException(ApiErrorCode.DEADLINE_PASSED);
        }
    }

    /** Account deleted or phone not verified: one answer, as the venue must not learn which (D4). */
    private boolean customerActive(CommandContext context, UUID customerId) {
        try {
            activation.requirePhoneVerified(context, customerId);
            return true;
        } catch (ApiException inactive) {
            return false;
        }
    }

    /** The one customer-ineligibility answer the venue ever gets: no details, no field (FR-LCF-05, NFR-19). */
    private static ApiException notEligible() {
        return new ApiException(ApiErrorCode.CUSTOMER_NOT_ELIGIBLE_FOR_LATE_CONFIRM);
    }

    private static AvailabilityPeriod period(Booking booking) {
        return new AvailabilityPeriod(booking.startAt(), booking.endAt());
    }

    /**
     * A broken Late Confirm invariant is a data defect, never a refusal (as E7-47 D11): ERROR with the booking and
     * correlation IDs; the thrown exception rolls the command back and the API answers a generic 500.
     */
    private static IllegalStateException integrityFailure(UUID bookingId, String detail) {
        log.error("Late Confirm integrity failure: {} (bookingId={}, correlationId={})", detail, bookingId,
                CorrelationId.current().map(UUID::toString).orElse("none"));
        return new IllegalStateException("Booking " + bookingId + " Late Confirm integrity failure: " + detail);
    }
}
