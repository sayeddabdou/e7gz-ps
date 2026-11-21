package ps.e7gz.operations.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.BookingTimerPauseKind;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.codes.OperationalRestriction;
import ps.e7gz.common.codes.ResourceBaseState;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.operations.api.EmergencyBookings;
import ps.e7gz.operations.api.EmergencyBookings.Live;
import ps.e7gz.operations.api.EmergencyBookings.PausedTimer;
import ps.e7gz.operations.api.UnresolvedEmergencies;
import ps.e7gz.resource.api.ResourceStates;
import ps.e7gz.scheduling.api.NewJob;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.venue.api.OperatingSessions;
import ps.e7gz.venue.api.OperatingSessions.SessionPeriod;
import ps.e7gz.venue.api.VenueEligibility;

/**
 * Emergency pause and resume of a booking's timers (E7-83, S5-E3; arch 08 section 2.5, FR-OPS-34…FR-OPS-41, Spec v5
 * section 44): the venue-response timer of a PENDING Online Booking and the Late Confirm countdown of an AWAITING one.
 *
 * <p><b>Pause</b> ({@link #pause}, from an Emergency's cascade): the booking's pause instant and its jobs PAUSED
 * ({@link EmergencyBookings#pause}), one open {@code operations.booking_timer_pause_history} row whose cause is the
 * Emergency's reason, {@code TimerPaused}, then the decision below at once - the feasibility test is evaluated whenever a
 * pause begins (FR-OPS-40).
 *
 * <p><b>The decision</b> ({@link #reconsider}), the same wherever it is made - at the pause, when an Emergency is ended
 * or converted, and by the {@code TIMER_PAUSE_CHECK} job - and pure over the stored state and the command instant:
 * <ol>
 *   <li><b>Cap</b> (FR-OPS-37, FR-OPS-38): the 30-minute counter - before this pause plus this pause - reached:
 *       CANCELLED at the instant it reached 30 minutes, never later (a late check never moves it, as a deadline): a
 *       response timer under {@code VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY} or
 *       {@code RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY} by the cause of the pause in force, a Late Confirm countdown
 *       under {@code LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY}. Venue and resource pauses feed ONE response counter, so
 *       switching type never resets it. A Late Confirm countdown still paused at {@code startAt} can never resume
 *       (FR-OPS-39) and is cancelled under the same reason at {@code startAt}.</li>
 *   <li><b>Cause</b>: while an unresolved Emergency still affects the booking (its venue's, or its Resource's - FR-OPS-34;
 *       for Late Confirm any active selling block too, FR-OPS-35) it stays paused. When the Emergency of the pause in
 *       force is resolved but another still affects it, the history row is closed and a new one opened under the other's
 *       reason at the same instant - the booking's pause and counter run on, so the cap is reached under the reason of
 *       the pause in force (the named case: 12 minutes venue + 19 minutes resource cancels at 30 under the resource
 *       reason).</li>
 *   <li><b>Feasibility</b> (FR-OPS-40, response timers only): assuming the block resolved now, could the remaining time
 *       fit a valid online intake window before {@code startAt} ({@link TimerFeasibility})? No: CANCELLED now under the
 *       cause's reason.</li>
 *   <li><b>Resume</b> (FR-OPS-39, FR-OPS-36): with no Emergency in force, a response timer resumes only when the Venue
 *       Account is ENABLED, the marketplace permits Accept (category B: any status but ONBOARDING, FR-BOK-15) and now is
 *       inside an online intake window; a Late Confirm countdown only before {@code startAt}, with the venue ENABLED and
 *       the Resource ACTIVE and unrestricted. The remaining time is preserved: the new deadline is now + remaining
 *       (Late Confirm: never beyond {@code startAt}), its jobs re-armed whether PAUSED or FAILED.</li>
 *   <li>Otherwise it waits, and a {@code TIMER_PAUSE_CHECK} job is due at the next instant the decision can change: the
 *       cap, one second after the last feasible resume, or the next intake window - so an Emergency ended while the
 *       venue is closed does not restart the timer; it waits for the next valid intake window (FR-OPS-39).</li>
 * </ol>
 *
 * <p>Every write runs on the caller's command, which holds the booking's Customer, Venue and Resource
 * ({@code begin_command}). Accept while paused keeps its refusal ({@code VenueAnswers}, PO decision 12 Oct 2026);
 * the customer may still Decline or cancel (FR-OPS-41, FR-BOK-32).
 */
@Component
class TimerPauses {

    private static final Logger log = LoggerFactory.getLogger(TimerPauses.class);

    /** FR-OPS-37: each of the two cumulative caps. */
    static final Duration CAP = Duration.ofMinutes(30);
    static final String CHECK_JOB = "TIMER_PAUSE_CHECK";
    static final String BOOKING_SUBJECT = "BOOKING";

    /** One second after the last feasible resume, no window fits any more (FR-OPS-40). */
    private static final Duration AFTER_LAST_FEASIBLE = Duration.ofSeconds(1);

    static final String OPEN_ROW = """
            SELECT sequence, pause_cause, pause_started_at
              FROM operations.booking_timer_pause_history
             WHERE booking_id = :booking AND pause_kind = :kind AND pause_ended_at IS NULL
            """;

    static final String INSERT_ROW = """
            INSERT INTO operations.booking_timer_pause_history (id, booking_id, sequence, pause_kind, pause_started_at,
                                                                pause_cause)
            SELECT :id, :booking, COALESCE(max(h.sequence), 0) + 1, :kind, :at, :cause
              FROM operations.booking_timer_pause_history h
             WHERE h.booking_id = :booking AND h.pause_kind = :kind
            """;

    static final String CLOSE_ROW = """
            UPDATE operations.booking_timer_pause_history
               SET pause_ended_at = :at, accumulated_duration = make_interval(secs => :seconds)
             WHERE booking_id = :booking AND pause_kind = :kind AND pause_ended_at IS NULL
            """;

    /** FR-OPS-35: an active selling block of the venue or of the booked room (a state, read as the cascade reads it). */
    static final String BLOCKED = """
            SELECT EXISTS (SELECT 1 FROM venue.venue_selling_block WHERE venue_id = :venue AND active)
                OR EXISTS (SELECT 1 FROM resource.resource_selling_block WHERE resource_id = :resource AND active)
            """;

    /** The end of a normal Closure in force at {@code :at}: the venue is not in a valid Operating Session then. */
    static final String CLOSED_UNTIL = """
            SELECT upper(c.period) FROM operations.venue_closure c
             WHERE c.venue_id = :venue AND c.deleted_at IS NULL AND c.period @> CAST(:at AS timestamptz)
             ORDER BY upper(c.period) DESC
             LIMIT 1
            """;

    private final EmergencyBookings bookings;
    private final UnresolvedEmergencies emergencies;
    private final OperatingSessions sessions;
    private final VenueEligibility venues;
    private final ResourceStates resources;
    private final ScheduledJobs jobs;
    private final Outbox outbox;

    TimerPauses(EmergencyBookings bookings, UnresolvedEmergencies emergencies, OperatingSessions sessions,
                VenueEligibility venues, ResourceStates resources, ScheduledJobs jobs, Outbox outbox) {
        this.bookings = bookings;
        this.emergencies = emergencies;
        this.sessions = sessions;
        this.venues = venues;
        this.resources = resources;
        this.jobs = jobs;
        this.outbox = outbox;
    }

    /** What a decision did. */
    enum Outcome { RESUMED, CANCELLED, PAUSED, NOT_PAUSED }

    /**
     * @param nextCheck when {@link Outcome#PAUSED}: the next instant the decision can change
     * @param reason    when {@link Outcome#CANCELLED}: the terminal reason
     */
    record Decision(Outcome outcome, Instant nextCheck, BookingTerminalReason reason) {

        static final Decision NOT_PAUSED = new Decision(Outcome.NOT_PAUSED, null, null);
    }

    // ---------------------------------------------------------------------------------------------- pause

    /**
     * FR-OPS-34, FR-OPS-35: pauses exactly {@code bookingIds} - unpaused PENDING or AWAITING Online Bookings the cascade of
     * {@code emergencyReason}'s Emergency leaves alive - each with its open history row and {@code TimerPaused}, then
     * decides each at once (cap, feasibility). Returns the bookings paused.
     */
    List<Live> pause(CommandContext context, UUID venueId, BookingTerminalReason emergencyReason,
                     List<UUID> bookingIds) {
        if (bookingIds.isEmpty()) {
            return List.of();
        }
        Instant now = context.commandNow();
        List<Live> paused = bookings.pause(context, venueId, bookingIds);
        for (Live booking : paused) {
            BookingTimerPauseKind kind = booking.status() == BookingStatus.PENDING ? BookingTimerPauseKind.VENUE_RESPONSE
                    : BookingTimerPauseKind.LATE_CONFIRM;
            openRow(context, booking.bookingId(), kind, emergencyReason, now);
            Map<String, Object> payload = payload(booking.bookingId(), venueId, booking.resourceId(),
                    booking.customerId(), kind);
            payload.put("pauseCause", emergencyReason.name());
            payload.put("pausedAt", now.toString());
            outbox.publish(context, new NewOutboxEvent(IntegrationEvents.TIMER_PAUSED, BOOKING_SUBJECT,
                    booking.bookingId(), payload));
        }
        for (Live booking : paused) {
            settle(context, booking.bookingId());
        }
        return paused;
    }

    /**
     * After an Emergency is ended or converted: every paused timer of its Venue (a venue Emergency) or of its Resource is
     * decided again now. A booking whose Customer or Resource {@code locks} do not hold - {@code locks} null: none - is
     * left to a {@code TIMER_PAUSE_CHECK} due now, which takes its own locks.
     */
    void afterResolution(CommandContext context, UUID venueId, UUID resourceId, LockSet locks) {
        Instant now = context.commandNow();
        for (PausedTimer timer : bookings.pausedTimers(context, venueId, resourceId)) {
            if (locks != null && locks.users().contains(timer.customerId())
                    && locks.resources().contains(timer.resourceId())) {
                settle(context, timer.bookingId());
            } else {
                scheduleCheck(context, timer.bookingId(), now);
            }
        }
    }

    /** {@link #reconsider}, and a check scheduled when it still waits. */
    Decision settle(CommandContext context, UUID bookingId) {
        Decision decision = reconsider(context, bookingId);
        if (decision.outcome() == Outcome.PAUSED) {
            scheduleCheck(context, bookingId, decision.nextCheck());
        }
        return decision;
    }

    // ---------------------------------------------------------------------------------------------- the decision

    /** The decision of the class comment, at the command instant. */
    Decision reconsider(CommandContext context, UUID bookingId) {
        Optional<PausedTimer> found = bookings.pausedTimer(context, bookingId);
        if (found.isEmpty()) {
            return Decision.NOT_PAUSED;
        }
        PausedTimer timer = found.get();
        Instant now = context.commandNow();
        OpenRow open = openRow(context, timer)
                .orElseThrow(() -> integrityFailure(bookingId, "a paused " + timer.kind() + " timer has no open pause row"));
        boolean response = timer.kind() == BookingTimerPauseKind.VENUE_RESPONSE;

        // 1. the cap (FR-OPS-37, FR-OPS-38) - and, for Late Confirm, startAt (FR-OPS-39)
        Instant capAt = timer.pauseStartedAt().plus(CAP.minus(timer.cumulative()));
        Instant bound = response || capAt.isBefore(timer.startAt()) ? capAt : timer.startAt();
        if (!now.isBefore(bound)) {
            if (bound.isBefore(open.startedAt())) {
                throw integrityFailure(bookingId, "the cap was reached before the pause in force began");
            }
            BookingTerminalReason reason = reasonOf(timer, open.cause());
            bookings.cancelInterrupted(context, bookingId, reason, bound);
            if (bound.equals(capAt)) {
                Map<String, Object> payload = payload(timer);
                payload.put("terminalReason", reason.name());
                payload.put("capReachedAt", capAt.toString());
                outbox.publish(context, new NewOutboxEvent(IntegrationEvents.TIMER_PAUSE_CAP_REACHED, BOOKING_SUBJECT,
                        bookingId, payload));
            }
            return new Decision(Outcome.CANCELLED, null, reason);
        }

        // 2. what holds it now; the cause follows the Emergency in force
        Optional<BookingTerminalReason> cause = emergencyInForce(context, timer, open.cause(), now);
        if (cause.isPresent() && !cause.get().name().equals(open.cause())) {
            switchCause(context, timer, cause.get(), now);
            open = new OpenRow(open.sequence() + 1, cause.get().name(), now);
        }
        boolean held = cause.isPresent() || (!response && blocked(context, timer));

        // 3. feasibility (FR-OPS-40): never a prediction of the block's end, only "if it resolved now"
        List<SessionPeriod> periods = response ? sessions.endingAfter(context, timer.venueId(), now) : List.of();
        Optional<Instant> lastFeasible = response
                ? TimerFeasibility.lastFeasibleResume(now, timer.remaining(), timer.startAt(), periods)
                : Optional.empty();
        if (response && lastFeasible.isEmpty()) {
            BookingTerminalReason reason = reasonOf(timer, open.cause());
            bookings.cancelInterrupted(context, bookingId, reason, now);
            return new Decision(Outcome.CANCELLED, null, reason);
        }

        // 4. resume (FR-OPS-39, FR-OPS-36)
        Optional<Instant> closedUntil = response ? closedUntil(context, timer.venueId(), now) : Optional.empty();
        if (!held && closedUntil.isEmpty() && resumable(context, timer, now, periods)) {
            Instant deadline = deadlineOnResume(timer, now);
            bookings.resume(context, bookingId, now, deadline);
            Map<String, Object> payload = payload(timer);
            payload.put("resumedAt", now.toString());
            payload.put("deadline", deadline.toString());
            payload.put("remainingSeconds", Duration.between(now, deadline).toSeconds());
            outbox.publish(context, new NewOutboxEvent(IntegrationEvents.TIMER_RESUMED, BOOKING_SUBJECT, bookingId,
                    payload));
            return new Decision(Outcome.RESUMED, null, null);
        }

        // 5. it waits: the next instant the decision can change
        Instant next = bound;
        if (lastFeasible.isPresent()) {
            next = earlier(next, lastFeasible.get().plus(AFTER_LAST_FEASIBLE));
        }
        if (!held && response) {
            Optional<Instant> intake = TimerFeasibility.nextIntakeWindow(now, periods);
            if (intake.isPresent()) {
                next = earlier(next, intake.get());
            }
            if (closedUntil.isPresent()) {
                next = earlier(next, closedUntil.get());
            }
        }
        return new Decision(Outcome.PAUSED, next, null);
    }

    private static Optional<Instant> closedUntil(CommandContext context, UUID venueId, Instant now) {
        return context.jdbc().sql(CLOSED_UNTIL).param("venue", venueId).param("at", utc(now))
                .query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant);
    }

    /**
     * FR-OPS-36: now + the remaining time - unchanged when nothing remained (the deadline had passed before the pause:
     * T1 or T2 then owns it at its stored deadline); a Late Confirm deadline never beyond {@code startAt}.
     */
    static Instant deadlineOnResume(PausedTimer timer, Instant now) {
        if (timer.remaining().isZero()) {
            return timer.deadline();
        }
        Instant deadline = now.plus(timer.remaining());
        if (timer.kind() == BookingTimerPauseKind.LATE_CONFIRM && deadline.isAfter(timer.startAt())) {
            deadline = timer.startAt();
        }
        return deadline.isAfter(timer.deadline()) ? deadline : timer.deadline();
    }

    /**
     * The reason of the Emergency that keeps the booking paused now: the pause in force's own while its kind is still
     * unresolved, else the other kind's; empty when none is. A Resource Emergency on another Resource pauses nothing
     * (FR-OPS-34).
     */
    private Optional<BookingTerminalReason> emergencyInForce(CommandContext context, PausedTimer timer, String current,
                                                             Instant now) {
        boolean resource = emergencies.resourceEmergency(context, timer.resourceId(), now);
        boolean venue = emergencies.venueEmergency(context, timer.venueId(), now);
        BookingTerminalReason resourceReason = BookingTerminalReason.RESOURCE_EMERGENCY_OUTAGE;
        BookingTerminalReason venueReason = BookingTerminalReason.VENUE_EMERGENCY_CLOSURE;
        if (resource && current.equals(resourceReason.name())) {
            return Optional.of(resourceReason);
        }
        if (venue && current.equals(venueReason.name())) {
            return Optional.of(venueReason);
        }
        if (resource) {
            return Optional.of(resourceReason);
        }
        return venue ? Optional.of(venueReason) : Optional.empty();
    }

    /** FR-OPS-35: a Late Confirm countdown also waits for any active selling block of the venue or the Resource. */
    private boolean blocked(CommandContext context, PausedTimer timer) {
        return Boolean.TRUE.equals(context.jdbc().sql(BLOCKED).param("venue", timer.venueId())
                .param("resource", timer.resourceId()).query(Boolean.class).single());
    }

    /** FR-OPS-39, besides the Emergency having ended. */
    private boolean resumable(CommandContext context, PausedTimer timer, Instant now, List<SessionPeriod> periods) {
        VenueEligibility.Eligibility venue = venues.find(context, timer.venueId())
                .orElseThrow(() -> integrityFailure(timer.bookingId(), "the venue vanished"));
        if (venue.accountStatus() != VenueAccountStatus.ENABLED) {
            return false;
        }
        if (timer.kind() == BookingTimerPauseKind.VENUE_RESPONSE) {
            return venue.marketplaceStatus() != MarketplaceStatus.ONBOARDING
                    && TimerFeasibility.insideIntakeWindow(now, periods);
        }
        if (!now.isBefore(timer.startAt())) {
            return false;
        }
        return resources.find(context, timer.resourceId())
                .filter(room -> room.baseState() == ResourceBaseState.ACTIVE
                        && room.restriction() == OperationalRestriction.NONE)
                .isPresent();
    }

    /**
     * The Emergency of the pause in force was resolved while another still affects the booking: its history row closed
     * and the next opened under the other's reason at {@code now}; the booking's pause and counter run on.
     */
    private void switchCause(CommandContext context, PausedTimer timer, BookingTerminalReason cause, Instant now) {
        Duration accumulated = timer.cumulative().plus(Duration.between(timer.pauseStartedAt(), now));
        closeRow(context, timer.bookingId(), timer.kind(), now, accumulated);
        openRow(context, timer.bookingId(), timer.kind(), cause, now);
    }

    // ---------------------------------------------------------------------------------------------- shared

    /** FR-OPS-38: the interrupted-by-emergency reason of the pause's cause. */
    static BookingTerminalReason reasonOf(PausedTimer timer, String cause) {
        if (timer.kind() == BookingTimerPauseKind.LATE_CONFIRM) {
            return BookingTerminalReason.LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY;
        }
        return BookingTerminalReason.RESOURCE_EMERGENCY_OUTAGE.name().equals(cause)
                ? BookingTerminalReason.RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY
                : BookingTerminalReason.VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY;
    }

    /** {@code TIMER_PAUSE_CHECK:{bookingId}:{dueEpochMillis}}: one per booking and instant, written at most once. */
    static String checkKey(UUID bookingId, Instant dueAt) {
        return CHECK_JOB + ":" + bookingId + ":" + dueAt.toEpochMilli();
    }

    void scheduleCheck(CommandContext context, UUID bookingId, Instant dueAt) {
        jobs.schedule(context, new NewJob(CHECK_JOB, BOOKING_SUBJECT, bookingId, dueAt, checkKey(bookingId, dueAt)));
    }

    private record OpenRow(int sequence, String cause, Instant startedAt) {
    }

    private static Optional<OpenRow> openRow(CommandContext context, PausedTimer timer) {
        return context.jdbc().sql(OPEN_ROW)
                .param("booking", timer.bookingId()).param("kind", timer.kind().name())
                .query((rs, n) -> new OpenRow(rs.getInt("sequence"), rs.getString("pause_cause"),
                        rs.getObject("pause_started_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    private static void openRow(CommandContext context, UUID bookingId, BookingTimerPauseKind kind,
                                BookingTerminalReason cause, Instant at) {
        context.jdbc().sql(INSERT_ROW)
                .param("id", UUID.randomUUID()).param("booking", bookingId).param("kind", kind.name())
                .param("at", utc(at)).param("cause", cause.name())
                .update();
    }

    private static void closeRow(CommandContext context, UUID bookingId, BookingTimerPauseKind kind, Instant at,
                                 Duration accumulated) {
        int closed = context.jdbc().sql(CLOSE_ROW)
                .param("at", utc(at)).param("seconds", accumulated.toNanos() / 1_000_000_000.0)
                .param("booking", bookingId).param("kind", kind.name())
                .update();
        if (closed != 1) {
            throw integrityFailure(bookingId, closed + " open " + kind + " pause rows, expected 1");
        }
    }

    private static Map<String, Object> payload(PausedTimer timer) {
        return payload(timer.bookingId(), timer.venueId(), timer.resourceId(), timer.customerId(), timer.kind());
    }

    private static Map<String, Object> payload(UUID bookingId, UUID venueId, UUID resourceId, UUID customerId,
                                               BookingTimerPauseKind kind) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", bookingId.toString());
        payload.put("venueId", venueId.toString());
        payload.put("resourceId", resourceId.toString());
        if (customerId != null) {
            payload.put("customerId", customerId.toString());
        }
        payload.put("pauseKind", kind.name());
        return payload;
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static IllegalStateException integrityFailure(UUID bookingId, String detail) {
        log.error("Timer pause integrity failure: {} (bookingId={}, correlationId={})", detail, bookingId,
                CorrelationId.current().map(UUID::toString).orElse("none"));
        return new IllegalStateException("Booking " + bookingId + " timer pause integrity failure: " + detail);
    }
}
