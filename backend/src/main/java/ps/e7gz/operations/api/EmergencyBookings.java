package ps.e7gz.operations.api;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.BookingTimerPauseKind;
import ps.e7gz.common.command.CommandContext;

/**
 * What an Emergency's cascade needs from the Bookings (E7-82; arch 06 section 2.5, FR-OPS-08, FR-OPS-12, FR-OPS-34,
 * FR-OPS-35, FR-OPS-44): find the live bookings it reaches, end them under its reason, and pause the timers of the
 * pending requests it does not cancel. Declared here and implemented by the Booking module, because Booking already
 * depends on Operations ({@link ResponseTimerPauses}, {@link UnresolvedEmergencies}) - a port in the caller's
 * {@code api}, no module cycle (CLAUDE.md section 3).
 *
 * <p>Every method runs on the caller's command transaction. A writing call needs the venue, the bookings' Resources and
 * every Online Booking's Customer in the command's lock set ({@code begin_command}); the database refuses anything
 * else. Nothing commits here: the whole cascade commits with the Emergency, or none of it does (FR-OPS-09).
 */
public interface EmergencyBookings {

    /**
     * The live bookings - PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED or ACTIVE, Online and Manual - of the Venue
     * ({@code resourceId} null: a venue Emergency) or of the one Resource, whose booked interval {@code [startAt, endAt)}
     * overlaps {@code [from, to)}: the set a cascade over that window must end (FR-OPS-08). A CONFIRMED booking whose
     * {@code startAt} has passed (T4 late) is in it. VENUE_RESPONSE_EXPIRED holds no reservation and is not. In start,
     * then id order. Read-only.
     */
    List<Live> overlapping(CommandContext command, UUID venueId, UUID resourceId, Instant from, Instant to);

    /**
     * The PENDING and AWAITING_CUSTOMER_CONFIRMATION Online Bookings of the Venue (or the Resource) that do NOT overlap
     * {@code [from, to)}: an unresolved Emergency leaves them alive but pauses their venue-response or Late Confirm timer
     * (FR-OPS-34, FR-OPS-35, FR-OPS-17; {@code operations.assert_emergency_cascade_complete}). {@link Live#paused} tells
     * which are paused already (by another Emergency). In start, then id order. Read-only.
     */
    List<Live> outsideTheWindow(CommandContext command, UUID venueId, UUID resourceId, Instant from, Instant to);

    /**
     * Ends exactly {@code bookingIds} - the set the caller computed and checked under its locks - under {@code reason}
     * ({@code VENUE_EMERGENCY_CLOSURE} or {@code RESOURCE_EMERGENCY_OUTAGE}), all at the command instant, actor VENUE
     * with {@code venueAccountId}:
     * <ul>
     *   <li>PENDING, AWAITING (its Late Confirmation closed under the Emergency reason, never
     *       {@code CUSTOMER_RECONFIRM_TIMEOUT} - FR-OPS-44) and CONFIRMED not yet due: CANCELLED, released, jobs
     *       withdrawn;</li>
     *   <li>CONFIRMED whose {@code startAt} has passed because T4 was late (PO decisions 19-20 Oct 2026, Spec v5 section
     *       31.7): first the start-time outcome T4 would have written, judged at {@code startAt} - its valid start, then
     *       the interruption below; or its start-time cancellation; when the start-time state does not decide, it is
     *       cancelled under {@code reason} as a booking not started - an Emergency is never refused;</li>
     *   <li>ACTIVE: interrupted - CANCELLED under {@code reason}, its reservation released at the command instant
     *       (FR-AVL-12), its completion withdrawn, its attendance resolved from the evidence (FR-ATT-10): a check-in is
     *       ATTENDED_INTERRUPTED with {@code BOOKING_ATTENDED_INTERRUPTED} (counter reset, no Verified Player progress,
     *       no points - FR-TRS-11), Late Present only ATTENDANCE_UNCONFIRMED, nothing NOT_APPLICABLE.</li>
     * </ul>
     * Every Online Booking gets its Booking Fee decision (FULL_REFUND, to the Wallet or as Points - never a card, PO 19
     * Nov 2026) in one Loyalty batch, and one {@code BookingCancelled}. An id that is not a live booking of {@code venueId}
     * is a caller defect: {@link IllegalStateException}, and the command rolls back.
     *
     * @return one outcome per booking, in id order
     */
    List<Outcome> end(CommandContext command, UUID venueId, UUID venueAccountId, List<UUID> bookingIds,
                      BookingTerminalReason reason);

    /**
     * E7-84 (FR-OPS-28, Spec v5 section 43.3): every live booking - PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED
     * or ACTIVE, Online and Manual - of the Resource, whatever its interval: an Owner Review leaves none live
     * ({@code operations.assert_owner_review_cascade_complete}). In start, then id order. Read-only.
     */
    List<Live> onResource(CommandContext command, UUID venueId, UUID resourceId);

    /**
     * E7-84 (FR-OPS-32, Spec v5 section 43.6): the VENUE_RESPONSE_EXPIRED Online Bookings of the Resource still before
     * their {@code startAt} - Late-Confirmable, holding no reservation, so outside the cascade. Read-only.
     */
    List<Live> expiredOnResource(CommandContext command, UUID venueId, UUID resourceId);

    /**
     * E7-84: ends exactly {@code bookingIds} under {@code RESOURCE_OWNER_REVIEW_REQUIRED} as a system operational
     * consequence (actor SYSTEM, no Owner Password - FR-OPS-29), as {@link #end} ends an Emergency's set, with what a
     * whole-resource cascade adds: a booking whose time already passed with its timers late gets the outcome those timers
     * would have written first, never an interruption after its end - an ACTIVE one past its {@code endAt} is completed
     * as of {@code endAt} (T5), a CONFIRMED one started at {@code startAt} (T4, as {@link #end}) and, past its end,
     * completed as of {@code endAt}. Such a completed booking's {@link Outcome#completed()} is true and it gets no fee
     * decision.
     */
    List<Outcome> endForOwnerReview(CommandContext command, UUID venueId, List<UUID> bookingIds);

    /**
     * E7-84 (PO decisions 19-20 Oct 2026, R1 "start first"; Spec v5 section 31.7): the CONFIRMED bookings of the
     * Resource whose {@code startAt} passed with T4 late, started as of {@code startAt} when T4's own resolution says
     * start - its job withdrawn, the start written as T4 writes it. Called before a write that may change the Resource's
     * current state (an Owner Review entry), because the start gates read that state: the start is judged before the
     * cause exists, as an Emergency's cascade judges it before the Emergency row. A booking whose resolution is not a
     * start is left as it is, for T4 or the cascade. Needs the bookings' Customers in the lock set.
     *
     * @return the bookings started, in id order
     */
    List<UUID> startOverdue(CommandContext command, UUID venueId, UUID resourceId);

    /**
     * E7-84 (FR-OPS-32, Spec v5 section 43.6): one {@code LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED} Booking
     * Event with {@code detailCode RESOURCE_OWNER_REVIEW_REQUIRED} on each of {@code bookingIds} - VENUE_RESPONSE_EXPIRED
     * Online Bookings of {@code venueId} - at the command instant, actor SYSTEM. Nothing else changes.
     */
    void lateConfirmEligibilityLost(CommandContext command, UUID venueId, List<UUID> bookingIds);

    /**
     * Pauses the running timer of exactly {@code bookingIds} at the command instant (FR-OPS-34, FR-OPS-35): a PENDING
     * Online Booking's venue-response timer, an AWAITING one's Late Confirm countdown - its pause instant on the booking
     * and its jobs {@code PAUSED}. The caller appends the open {@code operations.booking_timer_pause_history} row (it
     * owns that table). A booking that is not an unpaused PENDING or AWAITING Online Booking of {@code venueId} is a
     * caller defect: {@link IllegalStateException}.
     *
     * @return the paused bookings, in id order, with the timer each had
     */
    List<Live> pause(CommandContext command, UUID venueId, List<UUID> bookingIds);

    /**
     * E7-83: the paused timers - PENDING Online Bookings with a paused venue-response timer and AWAITING ones with a
     * paused Late Confirm countdown - of the Venue ({@code resourceId} null) or of the one Resource, in start, then id
     * order. Read-only.
     */
    List<PausedTimer> pausedTimers(CommandContext command, UUID venueId, UUID resourceId);

    /** E7-83: the booking's paused timer; empty when it is not a paused PENDING or AWAITING Online Booking. Read-only. */
    Optional<PausedTimer> pausedTimer(CommandContext command, UUID bookingId);

    /**
     * E7-83 (FR-OPS-36, FR-OPS-39, arch 08 section 2.5): the paused timer resumes at {@code at} with its deadline moved to
     * {@code deadline} - the pause added to its 30-minute counter and the pause column cleared, the open pause-history
     * row closed through {@link ResponseTimerPauses}, the deadline stored ({@code venue_response_expires_at}, or the open
     * Late Confirmation's) and its jobs re-armed at it whether {@code PAUSED} or {@code FAILED} (Sprint 2 review, PO
     * decision 12 Oct 2026). Needs the booking's Customer, Venue and Resource in the lock set.
     *
     * @return the counter including this pause
     * @throws IllegalStateException when the booking has no paused timer - a caller defect
     */
    Duration resume(CommandContext command, UUID bookingId, Instant at, Instant deadline);

    /**
     * E7-83 (FR-OPS-38, FR-OPS-40): a paused booking CANCELLED at {@code at} under {@code reason} -
     * {@code VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY} / {@code RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY} for a PENDING
     * one, {@code LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY} for an AWAITING one - actor SYSTEM (no venue non-response
     * blame): its pause ended and history row closed, its reservation or hold released, its jobs withdrawn, its Booking
     * Fee decision (a full refund, no penalty) and {@code BookingCancelled}. Needs the booking's Customer, Venue and
     * Resource in the lock set.
     *
     * @throws IllegalStateException when the booking has no paused timer or the reason does not fit it - a caller defect
     */
    void cancelInterrupted(CommandContext command, UUID bookingId, BookingTerminalReason reason, Instant at);

    /**
     * One paused timer (FR-OPS-37).
     *
     * @param pauseStartedAt {@code response_pause_started_at} or {@code late_confirm_pause_started_at}
     * @param cumulative     the 30-minute counter before this pause ({@code cumulative_emergency_pause_duration} or
     *                       {@code cumulative_late_confirm_emergency_pause_duration})
     * @param deadline       the stored deadline the pause stopped: {@code venue_response_expires_at} or the open Late
     *                       Confirmation's {@code expires_at}
     */
    record PausedTimer(UUID bookingId, UUID venueId, UUID resourceId, UUID customerId, BookingStatus status,
                       BookingTimerPauseKind kind, Instant startAt, Instant pauseStartedAt, Duration cumulative,
                       Instant deadline) {

        public PausedTimer {
            Objects.requireNonNull(bookingId, "bookingId");
            Objects.requireNonNull(venueId, "venueId");
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(startAt, "startAt");
            Objects.requireNonNull(pauseStartedAt, "pauseStartedAt");
            Objects.requireNonNull(cumulative, "cumulative");
            Objects.requireNonNull(deadline, "deadline");
        }

        /** FR-OPS-36: the time the timer had left when it paused; never negative. */
        public Duration remaining() {
            Duration left = Duration.between(pauseStartedAt, deadline);
            return left.isNegative() ? Duration.ZERO : left;
        }
    }

    /**
     * One live booking.
     *
     * @param customerId the Online Booking's Customer, whom a writing command must lock; {@code null} for Manual
     * @param paused     its venue-response or Late Confirm timer is paused already
     */
    record Live(UUID bookingId, UUID resourceId, UUID customerId, BookingSource source, BookingStatus status,
                Instant startAt, Instant endAt, boolean paused) {

        public Live {
            Objects.requireNonNull(bookingId, "bookingId");
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(startAt, "startAt");
            Objects.requireNonNull(endAt, "endAt");
        }
    }

    /**
     * How one booking ended.
     *
     * @param from         its status when the cascade reached it
     * @param startedFirst a late-T4 booking the cascade started at its {@code startAt} before interrupting it
     * @param reason       the terminal reason written: the cause's, or a start-time reason T4 would have written; null
     *                     when the booking was completed instead ({@link #completed()}, E7-84)
     * @param attendance   the Attendance Result an interruption or completion resolved; null when it never started
     * @param bookingFee   the Booking Fee snapshot (the full-refund amount of an Online Booking; zero for Manual)
     */
    record Outcome(UUID bookingId, UUID resourceId, BookingSource source, BookingStatus from, boolean startedFirst,
                   BookingTerminalReason reason, AttendanceResult attendance, BigDecimal bookingFee) {

        public Outcome {
            Objects.requireNonNull(bookingId, "bookingId");
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(bookingFee, "bookingFee");
        }

        /** E7-84: its time had passed, so it was COMPLETED as of its {@code endAt} - not cancelled, no refund. */
        public boolean completed() {
            return reason == null;
        }
    }
}
