package ps.e7gz.booking.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.codes.BookingEventType;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
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
     * is ACTIVE: a booking behind a delayed start is started first ({@link #resolveDelayedStart}; PO decision 19 Oct
     * 2026, R1). Writes the STATUS_TRANSITION history row by the venue account and withdraws the booking's
     * BOOKING_COMPLETE job, which the terminal status made unnecessary.
     * @return false when the booking is no longer {@code from} (nothing written)
     */
    boolean markNoShow(CommandContext command, UUID bookingId, BookingStatus from, Instant at, UUID venueAccountId);

    /**
     * The scheduler race of a venue's attendance action (decisions of Amr Heiba, 17 Oct 2026; PO decision 19 Oct 2026,
     * R1; FR-BOK-26: a booking never stays CONFIRMED past startAt): an Online Booking still CONFIRMED inside the
     * action's window because its BOOKING_START (T4) has not been processed yet. Resolves that start exactly as T4 would
     * have at {@code startAt}, with the start-time rules and the start transition of E7-60 (Spec v5 sections
     * 31.2-31.6), on the caller's transaction:
     * <ul>
     *   <li>{@link DelayedStart#STARTED} - the start job, still waiting, is withdrawn ({@code SUPERSEDED}, never fires)
     *       and the booking is started as T4 starts it: CONFIRMED -> ACTIVE with the SYSTEM history row dated
     *       {@code startAt}, {@code BookingStarted} and BOOKING_COMPLETE at {@code endAt}; the caller then records its
     *       action as on any ACTIVE booking;</li>
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
        STARTED,
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

    /**
     * E7-70: the next {@code BOOKING_EVENT} history row by a Platform Admin - {@code NO_SHOW_REVERSED} or
     * {@code ATTENDANCE_CORRECTED} at the reversal or correction instant, which
     * {@code attendance.assert_attendance_action_audit} matches with the Admin audit row and the Trust event (FR-ATT-11,
     * FR-ATT-13, FR-AUD-05). The booking's status is not changed here (arch 07 section 7.3).
     *
     * @return the history row id (a post-endAt reversal's fee decision rests on its {@code NO_SHOW_REVERSED} row)
     */
    UUID recordAdminEvent(CommandContext command, UUID bookingId, Instant at, BookingEventType event, UUID adminId);

    /**
     * E7-70: the Platform Admin's no-show reversal on the booking side (FR-ATT-12, Product v5.0 section 27.8): NO_SHOW
     * -> CANCELLED / {@code ADMIN_NO_SHOW_REVERSAL} when {@code at} is before the original {@code endAt}, NO_SHOW ->
     * COMPLETED at or after it, dated {@code at}, with the STATUS_TRANSITION history row by the Admin. The released
     * reservation is never re-acquired, so a customer who validly took the freed slot keeps it. No fee decision here:
     * the caller records it from the returned history row.
     *
     * @return the transition, or empty when the booking is no longer NO_SHOW (nothing written)
     */
    Optional<Reversal> reverseNoShow(CommandContext command, UUID bookingId, Instant at, UUID adminId);

    /**
     * E7-70: the booking's Booking Reason Code, for the Platform Admin's view of it after an attendance action; empty
     * when it has none (not terminal, COMPLETED or NO_SHOW) or there is no such booking.
     */
    Optional<BookingTerminalReason> terminalReason(CommandContext command, UUID bookingId);

    /** The status a no-show reversal moved the booking to and its STATUS_TRANSITION history row. */
    record Reversal(BookingStatus status, UUID historyId) {

        public Reversal {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(historyId, "historyId");
        }
    }

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
