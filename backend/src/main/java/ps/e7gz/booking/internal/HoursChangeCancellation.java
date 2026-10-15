package ps.e7gz.booking.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.LateConfirmClosures;
import ps.e7gz.booking.api.LateConfirmWindows;
import ps.e7gz.booking.internal.VenueAnswerStore.Facts;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.inventory.ledger.api.ActiveReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.loyalty.api.BookingFeeRefunds;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.operations.api.ResponseTimerPauses;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.venue.api.HoursChangeBookings.BookingFacts;

/**
 * The cancellations of an opening-hours change (O5; FR-VEN-23 step 8, FR-VEN-24, Spec v5.0 section 14.8; E7-113 Phase
 * 3B): every booking of the caller's validated affected set ends CANCELLED / VENUE_OPENING_HOURS_CHANGED in the
 * caller's command, after {@code begin_command} has locked the venue, the bookings' Resources and their Customers.
 * E7-113 Phase 3C adds AWAITING_CUSTOMER_CONFIRMATION (FR-VEN-28).
 *
 * <p>The whole batch is checked first and only then written, so an id that should not be here fails before any write;
 * either way the thrown {@link IllegalStateException} rolls the whole command back (D11 of E7-47: a caller or data
 * defect, ERROR, 500 - never a refusal the venue could act on; the stale-preview 409 is the Venue command's).
 *
 * <p>The check pass reads, for a PENDING or CONFIRMED booking, its own active BOOKING reservation; for an AWAITING one
 * (Phase 3C), which holds no active BOOKING reservation, its active {@code LATE_CONFIRM_HOLD} on the booking's Resource
 * and interval, and - in one {@link LateConfirmWindows#openDeadlines} read for the batch - that it has an open Late
 * Confirmation. Whether that hold is the very one the attempt records ({@code hold_reservation_id}) is not readable
 * here (the Late Confirmation module owns the table): {@link LateConfirmClosures#closeForCancellation} checks it in the
 * write pass and {@code tr_late_confirmation_integrity} again at commit; either failure rolls the whole command back.
 *
 * <p>Then, in booking-id order and at one instant, the command's {@code now}, each booking: an emergency-paused PENDING
 * timer's pause ends (FR-OPS-37, FR-OPS-41; {@code ck_booking_pause_state_scope} needs it before the booking leaves
 * PENDING); for an AWAITING booking, first its Late Confirmation is closed through
 * {@link LateConfirmClosures#closeForCancellation} - its paused window's pause ended (same CHECK, before it leaves
 * AWAITING), its hold released, its attempt closed without a Customer response, T2 and T3 superseded (Phase 3A); the
 * transition; the release of its own BOOKING reservation (none for AWAITING: the closure released its hold); the history
 * row; its jobs withdrawn - T1 while PENDING, T4 and T5 while CONFIRMED (a LEASED job is left to its worker, which
 * re-reads the booking; an AWAITING booking has neither, its T2/T3 went with the closure); and one
 * {@code BookingCancelled} - {@link BookingEvents#manualCancelled} with {@code source} MANUAL for a Manual Booking.
 * Last, the Booking Fee decisions of every Online Booking in one {@link BookingFeeRefunds#recordDecisions} batch, the
 * command's only Loyalty entry point: one Loyalty context serves every Customer. A Manual Booking has no customer and
 * no decision ({@code loyalty.assert_booking} skips MANUAL).
 *
 * <p>The database stays the authority on the transition ({@code tr_03_booking_transition_guard}), the reason, the fee
 * treatment ({@code booking.expected_fee_treatment}: FULL_REFUND) and {@code commitment_period}. The Operating Session
 * a booking snapshotted is never touched (Spec v5.0 section 14.6). No notification is made here: the customer's comes
 * from {@code BookingCancelled} through E7-55's consumer (PO decision 12 Oct 2026), and nothing is published for the
 * hours change itself (PO decision 3 Oct 2026).
 *
 * <p>E7-113 Phase 3B working decisions (Amr, 15 Oct 2026; pending Sayed's final review): D1 - the history actor is
 * VENUE with the authenticated Venue Account, as for the venue's own Manual cancellation (the change is the venue's
 * explicit operation, never SYSTEM); D2 - a PENDING booking past its response deadline whose T1 has not run is still
 * PENDING, holds its reservation and is cancelled, its T1 superseded - the expiry is not run here; D3 - an unexpected
 * id fails the whole command, never skipped.
 *
 * <p>E7-113 Phase 3C working decisions (Amr, 15 Oct 2026; NOT yet approved by Sayed - needed before the final merge):
 * C1 - an AWAITING booking's history actor is VENUE with the authenticated Venue Account, as D1; C2 (Option A) - an
 * AWAITING booking past its stored Late Confirm deadline whose T2 has not run is still AWAITING, holds its hold and is
 * cancelled with {@code VENUE_OPENING_HOURS_CHANGED}, its T2 superseded - no timeout is synthesised here. Open for
 * Sayed: FR-AVL-15 may give T2 the state once the deadline is reached ({@link #requireOpenAttempts} is the one place
 * that decision would change); C3 - a paused Late Confirm window ends at the command instant, its attempt's
 * {@code expires_at} and {@code warning_sent_at} stay as recorded (the Phase 3A closure; E7-83 is not touched).
 */
@Component
class HoursChangeCancellation {

    private static final Logger log = LoggerFactory.getLogger(HoursChangeCancellation.class);
    static final BookingTerminalReason REASON = BookingTerminalReason.VENUE_OPENING_HOURS_CHANGED;

    private final VenueAnswerStore store;
    private final ReservationLedger ledger;
    private final BookingFeeRefunds refunds;
    private final ScheduledJobs jobs;
    private final Outbox outbox;
    private final ResponseTimerPauses pauses;
    private final LateConfirmClosures closures;
    private final LateConfirmWindows windows;

    HoursChangeCancellation(VenueAnswerStore store, ReservationLedger ledger, BookingFeeRefunds refunds,
                            ScheduledJobs jobs, Outbox outbox, ResponseTimerPauses pauses,
                            LateConfirmClosures closures, LateConfirmWindows windows) {
        this.store = store;
        this.ledger = ledger;
        this.refunds = refunds;
        this.jobs = jobs;
        this.outbox = outbox;
        this.pauses = pauses;
        this.closures = closures;
        this.windows = windows;
    }

    /** The ids in the order they are cancelled; a null list or element or a repeated id is refused before any SQL. */
    static List<UUID> ordered(List<UUID> bookingIds) {
        Objects.requireNonNull(bookingIds, "bookingIds");
        Set<UUID> seen = new HashSet<>();
        for (UUID id : bookingIds) {
            Objects.requireNonNull(id, "bookingIds must not contain null");
            if (!seen.add(id)) {
                throw new IllegalArgumentException("Booking " + id + " appears twice in the batch");
            }
        }
        return bookingIds.stream().sorted().toList();
    }

    /**
     * @param ordered  distinct ids in cancellation order ({@link #ordered})
     * @param sessions the preview facts of those ids, for the Operating Session a Manual Booking needs
     */
    void cancel(CommandContext context, UUID venueId, UUID venueAccountId, List<UUID> ordered,
                Map<UUID, BookingFacts> sessions) {
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(venueAccountId, "venueAccountId");
        Instant now = context.commandNow();

        List<Cancellable> batch = new ArrayList<>(ordered.size());
        for (UUID bookingId : ordered) {
            batch.add(validated(context, bookingId, venueId, sessions.get(bookingId), now));
        }
        requireOpenAttempts(context, batch);

        List<BookingFeeRefunds.Decision> decisions = new ArrayList<>();
        for (Cancellable next : batch) {
            Facts booking = next.booking();
            UUID bookingId = booking.bookingId();
            BookingStatus from = booking.status();
            if (from == BookingStatus.AWAITING_CUSTOMER_CONFIRMATION) {
                closures.closeForCancellation(context, bookingId, REASON, now);
            }
            if (from == BookingStatus.PENDING && booking.responsePaused()) {
                Duration accumulated = store.endResponsePause(context, bookingId, now)
                        .orElseThrow(() -> integrityFailure(bookingId, "the paused response timer did not end"));
                pauses.closeOpen(context, bookingId, now, accumulated);
            }
            if (!store.terminate(context, bookingId, from, BookingStatus.CANCELLED, REASON, now, null)) {
                throw integrityFailure(bookingId, "the booking left " + from + " under the command's locks");
            }
            if (from != BookingStatus.AWAITING_CUSTOMER_CONFIRMATION) {
                ledger.release(context, next.reservation().id(), now, REASON.name());
            }
            UUID history = store.insertTransition(context, bookingId, now, from, BookingStatus.CANCELLED, REASON,
                    ActorType.VENUE, venueAccountId, CorrelationId.require());
            if (from == BookingStatus.PENDING) {
                jobs.supersede(context, VenueAnswers.venueResponseExpiryKey(bookingId));
            } else if (from == BookingStatus.CONFIRMED) {
                jobs.supersede(context, ManualBookingCommand.bookingStartKey(bookingId));
                jobs.supersede(context, ManualBookingCommand.bookingCompleteKey(bookingId));
            }
            if (booking.source() == BookingSource.ONLINE) {
                decisions.add(new BookingFeeRefunds.Decision(booking.customerId(), bookingId, history));
                outbox.publish(context, BookingEvents.cancelled(booking, REASON));
            } else {
                outbox.publish(context, BookingEvents.manualCancelled(booking, REASON));
            }
        }
        refunds.recordDecisions(context, decisions);
    }

    /**
     * One booking of the batch checked under the command's locks, with its own active reservation - the
     * {@code LATE_CONFIRM_HOLD} of an AWAITING booking; writes nothing.
     */
    private Cancellable validated(CommandContext context, UUID bookingId, UUID venueId, BookingFacts session,
                                  Instant now) {
        Facts booking = store.facts(context, bookingId)
                .orElseThrow(() -> integrityFailure(bookingId, "no such booking"));
        if (!booking.venueId().equals(venueId)) {
            throw integrityFailure(bookingId, "the booking is not of venue " + venueId);
        }
        if (session == null) {
            throw integrityFailure(bookingId, "the booking's Operating Session facts are missing");
        }
        BookingStatus status = booking.status();
        boolean cancellable = switch (status) {
            case PENDING -> booking.source() == BookingSource.ONLINE;
            case CONFIRMED -> booking.source() == BookingSource.ONLINE || session.insideOperatingSession();
            case AWAITING_CUSTOMER_CONFIRMATION -> booking.source() == BookingSource.ONLINE;
            case VENUE_RESPONSE_EXPIRED, ACTIVE, NO_SHOW, COMPLETED, REJECTED, CANCELLED -> false;
        };
        if (!cancellable) {
            throw integrityFailure(bookingId, status + " " + booking.source()
                    + (session.insideOperatingSession() ? "" : " outside an Operating Session")
                    + " booking is not cancelled by an hours change");
        }
        if (!booking.startAt().isAfter(now)) {
            throw integrityFailure(bookingId, "the booking does not start after the command instant");
        }
        if (booking.source() == BookingSource.ONLINE && booking.customerId() == null) {
            throw integrityFailure(bookingId, "Online booking without a Customer");
        }
        ReservationOwner owner = status == BookingStatus.AWAITING_CUSTOMER_CONFIRMATION
                ? ReservationOwner.lateConfirmHold(bookingId)
                : ReservationOwner.booking(bookingId);
        return new Cancellable(booking, ownReservation(context, booking, owner));
    }

    /**
     * Every AWAITING booking of the batch has an open Late Confirmation, in one read before any write. C2 (Option A,
     * provisional): its stored deadline is deliberately not compared with the command instant - a booking still
     * AWAITING is cancelled even past it. Were Sayed to give T2 the state at the deadline (FR-AVL-15), the check would
     * be added here.
     */
    private void requireOpenAttempts(CommandContext context, List<Cancellable> batch) {
        List<UUID> awaiting = batch.stream().map(Cancellable::booking)
                .filter(booking -> booking.status() == BookingStatus.AWAITING_CUSTOMER_CONFIRMATION)
                .map(Facts::bookingId)
                .toList();
        if (awaiting.isEmpty()) {
            return;
        }
        Map<UUID, Instant> open = windows.openDeadlines(context, awaiting);
        for (UUID bookingId : awaiting) {
            if (!open.containsKey(bookingId)) {
                throw integrityFailure(bookingId, "AWAITING booking has no open Late Confirmation");
            }
        }
    }

    /**
     * The booking's own active reservation of {@code owner} - its BOOKING reservation, or the LATE_CONFIRM_HOLD of an
     * AWAITING booking - on exactly its Resource and interval; anything else is a defect.
     */
    private ActiveReservation ownReservation(CommandContext context, Facts booking, ReservationOwner owner) {
        ActiveReservation reservation = ledger.activeOf(context, owner)
                .orElseThrow(() -> integrityFailure(booking.bookingId(),
                        booking.status() + " booking holds no active reservation"));
        if (!reservation.resourceId().equals(booking.resourceId())
                || !reservation.plannedStart().equals(booking.startAt())
                || !reservation.plannedEnd().equals(booking.endAt())) {
            throw integrityFailure(booking.bookingId(),
                    "the reservation does not match the booking's Resource and interval");
        }
        return reservation;
    }

    private static IllegalStateException integrityFailure(UUID bookingId, String detail) {
        log.error("Booking integrity failure: {} (bookingId={}, correlationId={})", detail, bookingId,
                CorrelationId.current().map(UUID::toString).orElse("none"));
        return new IllegalStateException("Booking " + bookingId + " integrity failure: " + detail);
    }

    private record Cancellable(Facts booking, ActiveReservation reservation) {
    }
}
