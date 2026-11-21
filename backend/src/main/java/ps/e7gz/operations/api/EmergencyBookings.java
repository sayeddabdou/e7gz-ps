package ps.e7gz.operations.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
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
     * @param reason       the terminal reason written: the Emergency's, or a start-time reason T4 would have written
     * @param attendance   the Attendance Result an interruption resolved; null when it never started
     * @param bookingFee   the Booking Fee snapshot (the full-refund amount of an Online Booking; zero for Manual)
     */
    record Outcome(UUID bookingId, UUID resourceId, BookingSource source, BookingStatus from, boolean startedFirst,
                   BookingTerminalReason reason, AttendanceResult attendance, BigDecimal bookingFee) {

        public Outcome {
            Objects.requireNonNull(bookingId, "bookingId");
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(bookingFee, "bookingFee");
        }
    }
}
