package ps.e7gz.attendance.internal;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ps.e7gz.admin.api.AdminAlerts;
import ps.e7gz.admin.api.NewAdminAlert;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.booking.api.AttendanceBookings;
import ps.e7gz.booking.api.AttendanceBookings.Booking;
import ps.e7gz.booking.api.AttendanceEvidence;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.inventory.ledger.api.ActiveReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.loyalty.api.BookingRewards;
import ps.e7gz.messaging.api.CommandAnswer;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.trust.api.BookingOutcomes;

/**
 * O13 - Check-in, Mark Present - Late and no-show (arch 07 section 7.1, FRS section 4.6; E7-59). Each method is a
 * command body: it runs after {@code begin_command} has locked the booking's Customer (with their Bookings, commitment
 * guard and Bans), Venue and Resource, so two actions on one booking never interleave; it re-reads the booking and its
 * attendance record and decides from that state and {@link CommandContext#commandNow()}, the evidence instant. The
 * evidence, its history row, its audit row and its integration event are written together - for a no-show also the
 * NO_SHOW transition, the release of the reservation and the {@code BOOKING_NO_SHOW} Trust outcome - and the database
 * checks the final state at commit ({@code tr_attendance_action_audit}, {@code tr_attendance_final_state}).
 *
 * <p>Order of the refusals: another venue's booking is NOT_FOUND; a Manual Booking is the action's own 422
 * (FR-MAN-04); a booking no longer in progress (terminal) is 409 BOOKING_STATE_CHANGED; outside the action's half-open
 * window, the action's 422 (FR-ATT-02: also before startAt and at exactly +15:00); a booking not ACTIVE inside the
 * window is first resolved as its delayed start (below), and is BOOKING_STATE_CHANGED when that start is not waiting
 * or cannot be resolved; blocking evidence is the action's 422 (FR-ATT-03, FR-ATT-04). Every refusal is thrown, so
 * nothing is written and nothing is stored under the Idempotency-Key. No Owner
 * Password (FR-ATT-01, FR-ATT-03, FR-ATT-04).
 */
@Component
class AttendanceCommands {

    private static final Logger log = LoggerFactory.getLogger(AttendanceCommands.class);

    /** FRS section 2.1: a booking in one of these is no longer in progress. */
    private static final Set<BookingStatus> TERMINAL = EnumSet.of(BookingStatus.COMPLETED, BookingStatus.REJECTED,
            BookingStatus.CANCELLED, BookingStatus.NO_SHOW);

    private final AttendanceBookings bookings;
    private final AttendanceStore store;
    private final ReservationLedger ledger;
    private final BookingOutcomes trust;
    private final BookingRewards rewards;
    private final AuditWriter audit;
    private final Outbox outbox;
    private final AdminAlerts alerts;

    AttendanceCommands(AttendanceBookings bookings, AttendanceStore store, ReservationLedger ledger,
                       BookingOutcomes trust, BookingRewards rewards, AuditWriter audit, Outbox outbox,
                       AdminAlerts alerts) {
        this.bookings = bookings;
        this.store = store;
        this.ledger = ledger;
        this.trust = trust;
        this.rewards = rewards;
        this.audit = audit;
        this.outbox = outbox;
        this.alerts = alerts;
    }

    /**
     * Records {@code action} for the booking at the command instant by the venue account (FR-ATT-01: audited with the
     * venue account, the timestamp and the Booking ID).
     */
    CommandAnswer<RecordedAttendance> record(CommandContext context, AttendanceAction action, UUID bookingId,
                                             UUID venueId, UUID venueAccountId) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(venueAccountId, "venueAccountId");
        Instant now = context.commandNow();
        Booking booking = bookings.find(context, bookingId)
                .filter(found -> found.venueId().equals(venueId))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        if (booking.source() != BookingSource.ONLINE) {
            throw new ApiException(action.refusal());                                           // FR-MAN-04
        }
        if (TERMINAL.contains(booking.status())) {
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }
        if (!withinWindow(action, booking, now)) {
            throw new ApiException(action.refusal());                                           // FR-ATT-02..04
        }
        BookingStatus from = booking.status();
        // The delayed-start race (Amr Heiba, 17 Oct 2026; PO decision 19 Oct 2026, R1): check-in, Mark Present - Late
        // and no-show behave alike. A CONFIRMED booking inside the action's window, its start not processed yet, is
        // first resolved exactly as T4 would have resolved it at startAt: it starts (T4's own transition - CONFIRMED ->
        // ACTIVE dated startAt, BookingStarted, BOOKING_COMPLETE at endAt, the start job withdrawn) and the action is
        // then recorded as on any ACTIVE booking; it would cancel (startCancelled: a committed 409, never a penalty);
        // or no start job is waiting (already run, withdrawn, missing, leased right now) or the start cannot be
        // resolved - refused, nothing written
        if (from != BookingStatus.ACTIVE) {
            if (from != BookingStatus.CONFIRMED) {
                throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
            }
            switch (bookings.resolveDelayedStart(context, bookingId)) {
                case STARTED -> from = BookingStatus.ACTIVE;
                case CANCELLED -> {
                    return startCancelled(context, bookingId, now);
                }
                case NOT_DELAYED, UNDETERMINED -> throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
            }
        }
        AttendanceStore.Evidence evidence = store.find(context, bookingId)
                .orElseGet(() -> AttendanceStore.Evidence.none(bookingId));
        if (!evidence.permits(action) || !store.record(context, action, bookingId, now, venueAccountId)) {
            throw new ApiException(action.refusal());                                           // FR-ATT-03, FR-ATT-04
        }

        BookingStatus status = from;
        if (action == AttendanceAction.NO_SHOW) {
            noShow(context, booking, from, now, venueAccountId);
            status = BookingStatus.NO_SHOW;
        }
        bookings.recordVenueEvent(context, bookingId, now, action.bookingEvent(), venueAccountId);
        AuditRecord.Builder record = AuditRecord.builder(action.auditAction(), AuditActor.venue(venueAccountId),
                bookingId, CorrelationId.require()).contextVenue(venueId);
        if (action == AttendanceAction.NO_SHOW) {
            record.stateChange(from.name(), BookingStatus.NO_SHOW.name());
        }
        audit.write(context, record.build());
        outbox.publish(context, AttendanceEvents.recorded(action, booking, now));

        AttendanceEvidence.Recorded after = JdbcAttendanceEvidence.recorded(store.find(context, bookingId)
                .orElseThrow(() -> new IllegalStateException("Attendance of booking " + bookingId + " vanished")));
        return CommandAnswer.ok(new RecordedAttendance(bookingId, status, after.result(), after.checkedInAt(),
                after.latePresentAt(), after.noShowAt()));
    }

    /**
     * The start would have cancelled the booking (Spec v5 section 31.3): no no-show evidence, no {@code BOOKING_NO_SHOW},
     * no penalty - only the attendance default of a booking cancelled before attendance, NOT_APPLICABLE (rule 6,
     * arch 07 section 7.3). The cancellation commits with this command and the venue is answered 409
     * BOOKING_STATE_CHANGED (Sprint 3 contract: the booking is no longer in progress), stored under the Idempotency-Key
     * so every replay answers the same; it is never reported as a no-show.
     */
    private CommandAnswer<RecordedAttendance> startCancelled(CommandContext context, UUID bookingId, Instant now) {
        AttendanceStore.Evidence evidence = store.find(context, bookingId)
                .orElseGet(() -> AttendanceStore.Evidence.none(bookingId));
        AttendanceResult result = AttendanceDefaults.resolve(BookingSource.ONLINE,
                AttendanceDefaults.Outcome.CANCELLED_OR_REJECTED, evidence);
        if (!store.resolve(context, bookingId, result, now)) {
            throw new IllegalStateException("Attendance of booking " + bookingId + " already resolved");
        }
        return CommandAnswer.refused(new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED));
    }

    /**
     * FR-ATT-04, FR-ATT-05: ACTIVE -> NO_SHOW (a delayed start already started) and the remaining interval released
     * at once - the reservation's release instant is the no-show instant, its planned interval and the booking's
     * {@code endAt} are never rewritten, so the room is sellable again from now under the normal rules. The
     * {@code BOOKING_NO_SHOW} Trust outcome is written in the same transaction (arch 07 section 2), with its Loyalty
     * reconciliation, which grants nothing for a no-show (the database requires both by commit). Terminal: venue staff
     * cannot undo it (FR-ATT-06).
     */
    private void noShow(CommandContext context, Booking booking, BookingStatus from, Instant now,
                        UUID venueAccountId) {
        if (!bookings.markNoShow(context, booking.bookingId(), from, now, venueAccountId)) {
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }
        ActiveReservation reservation = ledger.activeOf(context, ReservationOwner.booking(booking.bookingId()))
                .orElseThrow(() -> integrityFailure(booking.bookingId(), from + " booking holds no active reservation"));
        ledger.release(context, reservation.id(), now, BookingStatus.NO_SHOW.name());
        trust.recordNoShow(context, booking.customerId(), booking.bookingId(), booking.startAt());
        rewards.reconcile(context, booking.customerId(), booking.bookingId());
    }

    /** Arch 07 section 7.1: both windows half-open; a deadline belongs to the window it opens (FR-AVL-15). */
    static boolean withinWindow(AttendanceAction action, Booking booking, Instant now) {
        Instant lateOpensAt = booking.startAt().plus(AttendanceEvidence.CHECK_IN_WINDOW);
        return switch (action) {
            case CHECK_IN -> !now.isBefore(booking.startAt()) && now.isBefore(lateOpensAt);
            case LATE_PRESENT, NO_SHOW -> !now.isBefore(lateOpensAt) && now.isBefore(booking.endAt());
        };
    }

    /**
     * D11 (E7-47): an ACTIVE booking without its reservation is a data defect - logged at ERROR, the command rolled
     * back (a generic 500, nothing stored under the Idempotency-Key) and one SEV_1 admin alert raised after the
     * rollback (E7-56).
     */
    private IllegalStateException integrityFailure(UUID bookingId, String detail) {
        log.error("Booking integrity failure: {} (bookingId={}, correlationId={})", detail, bookingId,
                CorrelationId.current().map(UUID::toString).orElse("none"));
        CorrelationId.current().ifPresent(correlation -> alerts.raiseAfterRollback(
                NewAdminAlert.bookingIntegrityFailure(bookingId, detail, correlation)));
        return new IllegalStateException("Booking " + bookingId + " integrity failure: " + detail);
    }
}
