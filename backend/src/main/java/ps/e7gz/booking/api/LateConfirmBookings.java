package ps.e7gz.booking.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingEventType;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.command.CommandContext;

/**
 * The {@code booking.booking} side of a Late Confirmation (O3; arch 03 section 12, arch 06 section 2.2, FRS section
 * 4.5), for the Late Confirmation module ({@code booking.lateconfirm}), which owns {@code booking.late_confirmation}
 * and decides; Booking keeps its table, its transitions and its history (arch 03 section 1). Every method runs on the
 * caller's command transaction, after {@code begin_command} has locked the booking's Customer (with their Bookings,
 * commitment guard, Bans and Late Confirmations), Venue and Resource. The database stays the authority on every
 * transition ({@code tr_03_booking_transition_guard}), the terminal shape, the fee treatment and the
 * {@code commitment_period} it derives; each transition names the status it leaves, so a booking that moved on changes
 * nothing and the caller sees {@code false}.
 */
public interface LateConfirmBookings {

    /** The booking's decision facts, read on the command's transaction; empty when there is no such booking. */
    Optional<Booking> find(CommandContext command, UUID bookingId);

    /**
     * FR-BOK-03, FR-LCF-04, FR-LCF-14: the Customer's counted Online commitments - PENDING,
     * AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED and ACTIVE ({@code commitment_period IS NOT NULL}), the same count as
     * online booking creation.
     */
    long countCommitments(CommandContext command, UUID customerId);

    /**
     * FR-LCF-14, FR-LCF-15: whether another PENDING, CONFIRMED or ACTIVE commitment of the Customer overlaps
     * {@code [start, end)} - the customer schedule conflict, checked only on Accept. {@code bookingId} itself and the
     * Customer's other AWAITING bookings are left out (E7-58 Q1; FR-LCF-10): the first overlapping Accept wins.
     */
    boolean overlapsAnotherCommitment(CommandContext command, UUID customerId, UUID bookingId, Instant start,
                                      Instant end);

    /** VENUE_RESPONSE_EXPIRED -> AWAITING_CUSTOMER_CONFIRMATION (FR-LCF-06). @return false when no longer expired */
    boolean awaitCustomer(CommandContext command, UUID bookingId);

    /** AWAITING_CUSTOMER_CONFIRMATION -> CONFIRMED (FR-LCF-13). @return false when no longer AWAITING */
    boolean confirm(CommandContext command, UUID bookingId);

    /**
     * AWAITING_CUSTOMER_CONFIRMATION -> CANCELLED / CUSTOMER_DECLINED_LATE_CONFIRM at {@code at} (FR-LCF-13), with the
     * fee treatment the database derives for that reason. @return false when no longer AWAITING
     */
    boolean decline(CommandContext command, UUID bookingId, Instant at);

    /**
     * AWAITING_CUSTOMER_CONFIRMATION -> CANCELLED / CUSTOMER_RECONFIRM_TIMEOUT at {@code at}, the stored deadline - T2
     * (FR-LCF-13), with the fee treatment the database derives for that reason. @return false when no longer AWAITING
     */
    boolean timeOut(CommandContext command, UUID bookingId, Instant at);

    /**
     * Ends the AWAITING booking's Late Confirm pause at {@code at} (FR-OPS-37, FR-OPS-41): the pause is added to
     * {@code cumulative_late_confirm_emergency_pause_duration} and {@code late_confirm_pause_started_at} is cleared, as
     * {@code ck_booking_pause_state_scope} needs before the booking leaves AWAITING. Answers the cumulative duration;
     * empty when the booking is not a paused AWAITING booking.
     */
    Optional<Duration> endLateConfirmPause(CommandContext command, UUID bookingId, Instant at);

    /**
     * The next {@code STATUS_TRANSITION} history row (FR-AUD-05). @return its id - a terminal row is the basis of the
     * Booking Fee decision
     */
    UUID recordTransition(CommandContext command, Transition transition);

    /**
     * The next {@code BOOKING_EVENT} history row: a Late Confirm failure ({@code LATE_CONFIRM_FAILED_*}). The venue's
     * view of the history never shows {@code LATE_CONFIRM_FAILED_BOOKING_LIMIT} or
     * {@code LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT} (NFR-19, arch 10 section 5.2).
     */
    void recordEvent(CommandContext command, UUID bookingId, Instant at, BookingEventType event, ActorType actorType,
                     UUID actorId);

    /** Schedules T4, the booking start, at {@code startAt} - the same job the venue's Accept schedules. */
    void scheduleStart(CommandContext command, UUID bookingId, Instant startAt);

    /**
     * A booking as a Late Confirmation decides from it.
     *
     * @param lateConfirmPaused {@code late_confirm_pause_started_at IS NOT NULL}: an emergency pauses the window
     */
    record Booking(UUID bookingId, UUID venueId, UUID resourceId, UUID customerId, BookingSource source,
                   BookingStatus status, Instant startAt, Instant endAt, boolean lateConfirmPaused) {

        public Booking {
            Objects.requireNonNull(bookingId, "bookingId");
            Objects.requireNonNull(venueId, "venueId");
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(startAt, "startAt");
            Objects.requireNonNull(endAt, "endAt");
        }
    }

    /**
     * One status transition of the history.
     *
     * @param terminalReason only when {@code to} is terminal (CANCELLED here)
     * @param actorId        the Venue Account for VENUE, the Customer for CUSTOMER, {@code null} for SYSTEM
     */
    record Transition(UUID bookingId, Instant at, BookingStatus from, BookingStatus to,
                      BookingTerminalReason terminalReason, ActorType actorType, UUID actorId,
                      UUID correlationId) {

        public Transition {
            Objects.requireNonNull(bookingId, "bookingId");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(actorType, "actorType");
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }
}
