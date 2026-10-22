package ps.e7gz.booking.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.codes.BookingEventType;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.CommandContext;

/**
 * The {@code booking.booking} side of the venue's attendance actions (O13; arch 07 section 7.1, FRS section 4.6), for
 * the Attendance module, which owns {@code attendance.attendance_record} and decides; Booking keeps its table, its
 * transitions and its history (arch 03 section 1). Every method runs on the caller's command transaction, after
 * {@code begin_command} has locked the booking's Customer (with their Bookings, commitment guard and Bans), Venue and
 * Resource. The database stays the authority on every write ({@code tr_03_booking_transition_guard}, the attendance
 * guards); a transition names the status it leaves, so a booking that moved on changes nothing and the caller sees
 * {@code false}.
 */
public interface AttendanceBookings {

    /** The booking's decision facts, read on the command's transaction; empty when there is no such booking. */
    Optional<Booking> find(CommandContext command, UUID bookingId);

    /**
     * {@code from} -> NO_SHOW at {@code at} (FR-ATT-04, FR-ATT-05): terminal, with no Booking Reason Code and
     * {@code terminal_at = at}; the planned interval ({@code start_at}, {@code end_at}) is never rewritten. {@code from}
     * is ACTIVE, or CONFIRMED once the caller has withdrawn a delayed start ({@link #withdrawDelayedStart}). Writes the
     * STATUS_TRANSITION history row by the venue account and withdraws the booking's BOOKING_COMPLETE job, which the
     * terminal status made unnecessary. @return false when the booking is no longer {@code from} (nothing written)
     */
    boolean markNoShow(CommandContext command, UUID bookingId, BookingStatus from, Instant at, UUID venueAccountId);

    /**
     * The scheduler race of a no-show (decisions of Amr Heiba, acting decision-maker, 17 Oct 2026; FR-BOK-26: a booking
     * never stays CONFIRMED past startAt): an Online Booking still CONFIRMED inside the no-show window because its
     * BOOKING_START (T4) has not been processed yet. Resolves that start as T4 would have at {@code startAt}, with the
     * start-time rules of E7-60 (Spec v5 sections 31.2-31.6), on the caller's transaction:
     * <ul>
     *   <li>{@link DelayedStart#WOULD_START} - the start job, still waiting, is withdrawn ({@code SUPERSEDED}, never
     *       fires); the caller may move CONFIRMED -> NO_SHOW;</li>
     *   <li>{@link DelayedStart#CANCELLED} - the start would have cancelled: the start job is withdrawn and the booking is
     *       CANCELLED under its start-time reason, dated {@code startAt}, with its reservation released, its full-refund
     *       fee decision, {@code BookingCancelled} and, for {@code SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR}, the SEV_1 alert
     *       - never a no-show, never a penalty;</li>
     *   <li>{@link DelayedStart#NOT_DELAYED} - no start job is waiting (none, already run or withdrawn, or a worker holds
     *       it {@code LEASED} right now), so CONFIRMED is not explained by a delayed start; nothing written;</li>
     *   <li>{@link DelayedStart#UNDETERMINED} - the state at {@code startAt} does not determine one start-time outcome;
     *       nothing written.</li>
     * </ul>
     */
    DelayedStart resolveDelayedStart(CommandContext command, UUID bookingId);

    /** What a delayed start resolved to; see {@link #resolveDelayedStart}. */
    enum DelayedStart {
        WOULD_START,
        CANCELLED,
        NOT_DELAYED,
        UNDETERMINED
    }

    /**
     * The next {@code BOOKING_EVENT} history row by the venue account: {@code CHECK_IN_RECORDED},
     * {@code LATE_PRESENT_RECORDED} or {@code NO_SHOW_RECORDED} at the evidence instant (FR-ATT-01, FR-AUD-05).
     */
    void recordVenueEvent(CommandContext command, UUID bookingId, Instant at, BookingEventType event,
                          UUID venueAccountId);

    /** A booking as an attendance action decides from it. {@code customerId} is null for a Manual Booking. */
    record Booking(UUID bookingId, UUID venueId, UUID resourceId, UUID customerId, BookingSource source,
                   BookingStatus status, Instant startAt, Instant endAt) {

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
}
