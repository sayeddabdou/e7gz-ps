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
 *
 * <p>The whole batch is checked first and only then written, so an id that should not be here fails before any write;
 * either way the thrown {@link IllegalStateException} rolls the whole command back (D11 of E7-47: a caller or data
 * defect, ERROR, 500 - never a refusal the venue could act on; the stale-preview 409 is the Venue command's).
 *
 * <p>Then, in booking-id order and at one instant, the command's {@code now}, each booking: an emergency-paused PENDING
 * timer's pause ends (FR-OPS-37, FR-OPS-41; {@code ck_booking_pause_state_scope} needs it before the booking leaves
 * PENDING); the transition; the release of its own BOOKING reservation; the history row; its jobs withdrawn - T1 while
 * PENDING, T4 and T5 while CONFIRMED (a LEASED job is left to its worker, which re-reads the booking); and one
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
 * id fails the whole command, never skipped. AWAITING_CUSTOMER_CONFIRMATION is Phase 3C and refused until then.
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

    HoursChangeCancellation(VenueAnswerStore store, ReservationLedger ledger, BookingFeeRefunds refunds,
                            ScheduledJobs jobs, Outbox outbox, ResponseTimerPauses pauses) {
        this.store = store;
        this.ledger = ledger;
        this.refunds = refunds;
        this.jobs = jobs;
        this.outbox = outbox;
        this.pauses = pauses;
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

        List<BookingFeeRefunds.Decision> decisions = new ArrayList<>();
        for (Cancellable next : batch) {
            Facts booking = next.booking();
            UUID bookingId = booking.bookingId();
            BookingStatus from = booking.status();
            if (from == BookingStatus.PENDING && booking.responsePaused()) {
                Duration accumulated = store.endResponsePause(context, bookingId, now)
                        .orElseThrow(() -> integrityFailure(bookingId, "the paused response timer did not end"));
                pauses.closeOpen(context, bookingId, now, accumulated);
            }
            if (!store.terminate(context, bookingId, from, BookingStatus.CANCELLED, REASON, now, null)) {
                throw integrityFailure(bookingId, "the booking left " + from + " under the command's locks");
            }
            ledger.release(context, next.reservation().id(), now, REASON.name());
            UUID history = store.insertTransition(context, bookingId, now, from, BookingStatus.CANCELLED, REASON,
                    ActorType.VENUE, venueAccountId, CorrelationId.require());
            if (from == BookingStatus.PENDING) {
                jobs.supersede(context, VenueAnswers.venueResponseExpiryKey(bookingId));
            } else {
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

    /** One booking of the batch checked under the command's locks, with its own active reservation; writes nothing. */
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
            case AWAITING_CUSTOMER_CONFIRMATION -> throw integrityFailure(bookingId,
                    "an AWAITING_CUSTOMER_CONFIRMATION booking is not cancelled by an hours change yet (Phase 3C)");
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
        return new Cancellable(booking, ownReservation(context, booking));
    }

    /** The booking's own active BOOKING reservation on exactly its Resource and interval; anything else is a defect. */
    private ActiveReservation ownReservation(CommandContext context, Facts booking) {
        ActiveReservation reservation = ledger.activeOf(context, ReservationOwner.booking(booking.bookingId()))
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
