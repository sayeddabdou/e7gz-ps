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
     * ACTIVE -> NO_SHOW at {@code at} (FR-ATT-04, FR-ATT-05): terminal, with no Booking Reason Code and
     * {@code terminal_at = at}; the planned interval ({@code start_at}, {@code end_at}) is never rewritten. Writes the
     * STATUS_TRANSITION history row by the venue account and withdraws the booking's BOOKING_COMPLETE job, which the
     * terminal status made unnecessary. @return false when the booking is no longer ACTIVE (nothing written)
     */
    boolean markNoShow(CommandContext command, UUID bookingId, Instant at, UUID venueAccountId);

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
