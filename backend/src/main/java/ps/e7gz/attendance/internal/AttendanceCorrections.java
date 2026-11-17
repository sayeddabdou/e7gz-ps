package ps.e7gz.attendance.internal;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.booking.api.AttendanceBookings;
import ps.e7gz.booking.api.AttendanceBookings.Booking;
import ps.e7gz.booking.api.AttendanceBookings.Reversal;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingEventType;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.loyalty.api.BookingFeeRefunds;
import ps.e7gz.loyalty.api.BookingRewards;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.trust.api.TrustCorrections;

/**
 * The Platform Admin's Attendance correction and no-show reversal (arch 07 sections 3.3, 7.3 and 7.4; Product v5.0
 * sections 27.8-27.9; FR-ATT-09, FR-ATT-11..13, FR-TRS-07..09; E7-70). The Admin always chooses the outcome
 * explicitly - there is no generic undo (arch 09 section 5) - and gives a mandatory reason; venue staff can do neither
 * (FR-ATT-11: these are Admin commands only). Each runs as one admin command under the Customer and Venue locks
 * ({@code admin.begin_command}, which refuses an unknown or inactive Admin): every booking, attendance, history, audit,
 * Trust and Loyalty write commits together or not at all, and the database checks the final state
 * ({@code attendance.assert_attendance_action_audit}, {@code attendance.assert_attendance_row},
 * {@code trust.assert_user_integrity}, {@code loyalty.assert_booking}). The public entry points take the
 * Idempotency-Key (ADR-015: both may move points and the reversal money), so a retry returns the first answer.
 * The four routes of the Sprint 4 contract (E7-65, D1) are in {@code attendance.web.AdminAttendanceController}.
 *
 * <p><b>Evidence reversal</b> (arch 07 section 7.4, FR-ATT-12) - recorded evidence is never erased; the reversal adds
 * its marker with the Admin Booking Event and audit row:
 * <ul>
 *   <li>a check-in on an ACTIVE, unresolved booking: the booking stays ACTIVE and Mark Present - Late and no-show are
 *       open again inside their window; nothing was granted yet, so nothing is clawed back;</li>
 *   <li>a check-in behind a resolved ATTENDED: the result becomes ATTENDANCE_UNCONFIRMED with its
 *       {@code ATTENDANCE_CORRECTED}, and the reward is compensated (FR-TRS-08, FR-PTS-03);</li>
 *   <li>a Mark Present - Late on an ACTIVE, unresolved booking before its endAt: no-show is open again.</li>
 * </ul>
 * Any other state - no such evidence standing, a check-in behind another result, a Late Present after the booking
 * ended - is refused: the Admin corrects the outcome instead.
 *
 * <p><b>Correction</b> - a resolved result is replaced at the command instant with its {@code ATTENDANCE_CORRECTED},
 * which supersedes the previous correction, so a chain stays one effective outcome; the reward is granted or compensated
 * at the original completion configuration (FR-TRS-08). The status never changes (FR-ATT-09). A standing no-show must
 * be reversed instead.
 *
 * <p><b>No-show reversal</b> - the no-show stays recorded and gains its reversal marker, with the canonical
 * {@code NO_SHOW_REVERSED} targeting the original {@code BOOKING_NO_SHOW} at its own position (FR-TRS-05):
 * <ul>
 *   <li>before the original endAt the booking becomes CANCELLED / {@code ADMIN_NO_SHOW_REVERSAL}; at or after it,
 *       COMPLETED (FR-ATT-12);</li>
 *   <li>the Admin's choice (section 27.9): ATTENDANCE_UNCONFIRMED (penalty removal only), ATTENDED_INTERRUPTED
 *       (attended, resource reallocated) or ATTENDED (attended, session completed). The post-endAt default
 *       ATTENDANCE_UNCONFIRMED needs nothing more; every other choice - and every pre-endAt one, whose stored result
 *       would otherwise stay ABSENT_NO_SHOW - is the further Attendance Correction of section 27.8, written in the same
 *       command;</li>
 *   <li>the released reservation is never re-acquired, so no later customer is displaced; points come only from the
 *       chosen ATTENDED, through the reward reconciliation, never from the reversal itself;</li>
 *   <li>the fee decision rests on the Admin's explicit, audited service-loss assessment: FULL only when service loss
 *       is confirmed, otherwise nothing (FR-PTS-15).</li>
 * </ul>
 *
 * <p>Refusals, before anything is written: a blank reason, an outcome outside the allowed ones or a missing service-loss
 * assessment is {@code VALIDATION_FAILED}; an unknown booking is {@code NOT_FOUND}; a Manual Booking, an unresolved
 * attendance, a correction of a standing no-show, a reversal of a booking that is not (or no longer) NO_SHOW - a
 * duplicate reversal - or the result it already has is {@code ADMIN_ACTION_NOT_ALLOWED} (Sprint 4 contract, PO decision D5).
 *
 * <p>E7-71: a reversal or correction that leaves an operational Ban without replay support, or creates a new crossing,
 * is handed to the Ban reconciliation; until it exists the whole command rolls back
 * ({@code TrustConsequenceUnavailableException}).
 */
@Component
public class AttendanceCorrections {

    /** ck_trust_event_corrected_outcome (FR-TRS-07); NOT_APPLICABLE is never chosen. */
    private static final Set<AttendanceResult> CORRECTABLE = EnumSet.of(AttendanceResult.ATTENDED,
            AttendanceResult.ATTENDED_INTERRUPTED, AttendanceResult.ATTENDANCE_UNCONFIRMED,
            AttendanceResult.ABSENT_NO_SHOW);

    /** Product v5.0 section 27.9: the three choices after a wrong no-show. */
    private static final Set<AttendanceResult> AFTER_WRONG_NO_SHOW = EnumSet.of(AttendanceResult.ATTENDED,
            AttendanceResult.ATTENDED_INTERRUPTED, AttendanceResult.ATTENDANCE_UNCONFIRMED);

    private final CommandRunner runner;
    private final Idempotency idempotency;
    private final AttendanceBookings bookings;
    private final AttendanceStore store;
    private final TrustCorrections trust;
    private final BookingRewards rewards;
    private final BookingFeeRefunds refunds;
    private final AuditWriter audit;
    private final Outbox outbox;

    AttendanceCorrections(CommandRunner runner, Idempotency idempotency, AttendanceBookings bookings,
                          AttendanceStore store, TrustCorrections trust, BookingRewards rewards,
                          BookingFeeRefunds refunds, AuditWriter audit, Outbox outbox) {
        this.runner = runner;
        this.idempotency = idempotency;
        this.bookings = bookings;
        this.store = store;
        this.trust = trust;
        this.rewards = rewards;
        this.refunds = refunds;
        this.audit = audit;
        this.outbox = outbox;
    }

    /** {@code POST .../attendance/correct}: the correction once per Idempotency-Key, answered with the new state. */
    public <R> IdempotentResult<R> correct(UUID adminId, UUID bookingId, AttendanceResult outcome, String reason,
                                           IdempotentRequest key, StoredResponse<R> response,
                                           Function<AttendanceState, R> view) {
        return idempotent(thenState(correction(adminId, bookingId, outcome, reason), bookingId), key, response, view);
    }

    /**
     * {@code POST .../attendance/reverse-no-show}: the reversal once per Idempotency-Key. The route takes a reason only
     * (Sprint 4 contract), so it is the penalty removal alone - ATTENDANCE_UNCONFIRMED; to record that the customer
     * attended the Admin then uses {@code correct} (FR-ATT-13) - and it establishes no service loss, so the reversal
     * alone refunds nothing (FRS reason map, {@code ADMIN_NO_SHOW_REVERSAL}).
     */
    public <R> IdempotentResult<R> reverseNoShow(UUID adminId, UUID bookingId, String reason, IdempotentRequest key,
                                                 StoredResponse<R> response, Function<AttendanceState, R> view) {
        return idempotent(thenState(reversal(adminId, bookingId, AttendanceResult.ATTENDANCE_UNCONFIRMED, false,
                reason), bookingId), key, response, view);
    }

    /**
     * The reversal with the Admin's explicit choices of Product v5.0 section 27.9 - the outcome and the service-loss
     * assessment - once per Idempotency-Key. Package-private: the Sprint 4 route carries neither (see above), so
     * nothing outside this package reaches it yet.
     */
    <R> IdempotentResult<R> reverseNoShow(UUID adminId, UUID bookingId, AttendanceResult outcome, Boolean serviceLoss,
                                          String reason, IdempotentRequest key, StoredResponse<R> response,
                                          Function<ReversedNoShow, R> view) {
        return idempotent(reversal(adminId, bookingId, outcome, serviceLoss, reason), key, response, view);
    }

    /** {@code POST .../attendance/reverse-check-in}, once per Idempotency-Key. */
    public <R> IdempotentResult<R> reverseCheckIn(UUID adminId, UUID bookingId, String reason, IdempotentRequest key,
                                                  StoredResponse<R> response, Function<AttendanceState, R> view) {
        return idempotent(checkInReversal(adminId, bookingId, reason), key, response, view);
    }

    /** {@code POST .../attendance/reverse-late-present}, once per Idempotency-Key. */
    public <R> IdempotentResult<R> reverseLatePresent(UUID adminId, UUID bookingId, String reason,
                                                      IdempotentRequest key, StoredResponse<R> response,
                                                      Function<AttendanceState, R> view) {
        return idempotent(latePresentReversal(adminId, bookingId, reason), key, response, view);
    }

    /** The check-in reversal without an Idempotency-Key record: package-private, for the tests of this package. */
    AttendanceState reverseCheckIn(UUID adminId, UUID bookingId, String reason) {
        return runner.run(checkInReversal(adminId, bookingId, reason));
    }

    /** The Late Present reversal without an Idempotency-Key record: package-private, for the tests of this package. */
    AttendanceState reverseLatePresent(UUID adminId, UUID bookingId, String reason) {
        return runner.run(latePresentReversal(adminId, bookingId, reason));
    }

    /** The correction without an Idempotency-Key record: package-private, for this package's tests. */
    CorrectedAttendance correct(UUID adminId, UUID bookingId, AttendanceResult outcome, String reason) {
        return runner.run(correction(adminId, bookingId, outcome, reason));
    }

    /** The reversal without an Idempotency-Key record: package-private, for this package's tests. */
    ReversedNoShow reverseNoShow(UUID adminId, UUID bookingId, AttendanceResult outcome, Boolean serviceLoss,
                                 String reason) {
        return runner.run(reversal(adminId, bookingId, outcome, serviceLoss, reason));
    }

    private <T, R> IdempotentResult<R> idempotent(Command<T> command, IdempotentRequest key,
                                                  StoredResponse<R> response, Function<T, R> view) {
        Objects.requireNonNull(view, "view");
        return runner.run(idempotency.wrap(Objects.requireNonNull(key, "key"), response,
                new Body<>(command.scope(), context -> view.apply(command.execute(context)))));
    }

    private Command<CorrectedAttendance> correction(UUID adminId, UUID bookingId, AttendanceResult outcome,
                                                    String reason) {
        if (outcome == null || !CORRECTABLE.contains(outcome)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        }
        String why = reason(reason);
        Booking booking = onlineBooking(adminId, bookingId);
        return new Body<>(scopeOf(adminId, booking), context -> correct(context, adminId, booking, outcome, why));
    }

    private Command<ReversedNoShow> reversal(UUID adminId, UUID bookingId, AttendanceResult outcome,
                                             Boolean serviceLoss, String reason) {
        if (outcome == null || !AFTER_WRONG_NO_SHOW.contains(outcome) || serviceLoss == null) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        }
        String why = reason(reason);
        Booking booking = onlineBooking(adminId, bookingId);
        return new Body<>(scopeOf(adminId, booking),
                context -> reverse(context, adminId, booking, outcome, serviceLoss, why));
    }

    private Command<AttendanceState> checkInReversal(UUID adminId, UUID bookingId, String reason) {
        String why = reason(reason);
        Booking booking = onlineBooking(adminId, bookingId);
        return new Body<>(scopeOf(adminId, booking), context -> reverseCheckIn(context, adminId, booking, why));
    }

    private Command<AttendanceState> latePresentReversal(UUID adminId, UUID bookingId, String reason) {
        String why = reason(reason);
        Booking booking = onlineBooking(adminId, bookingId);
        return new Body<>(scopeOf(adminId, booking), context -> reverseLatePresent(context, adminId, booking, why));
    }

    /** The command, then the attendance of the booking as it left it, read in the same transaction. */
    private <T> Command<AttendanceState> thenState(Command<T> command, UUID bookingId) {
        return new Body<>(command.scope(), context -> {
            command.execute(context);
            return state(context, bookingId);
        });
    }

    /** FR-ATT-11: mandatory. */
    private static String reason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        }
        return reason.strip();
    }

    private Booking onlineBooking(UUID adminId, UUID bookingId) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(bookingId, "bookingId");
        Booking booking = runner.run(new Body<Optional<Booking>>(CommandScope.shared(LockSet.empty()),
                        context -> bookings.find(context, bookingId)))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        if (booking.source() != BookingSource.ONLINE || booking.customerId() == null) {
            throw new ApiException(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);
        }
        return booking;
    }

    /** A booking never changes Customer or Venue, so the locks read before the command still hold under it. */
    private static CommandScope scopeOf(UUID adminId, Booking booking) {
        return CommandScope.admin(adminId, CorrelationId.require(),
                LockSet.builder().users(booking.customerId()).venues(booking.venueId()).build());
    }

    private CorrectedAttendance correct(CommandContext context, UUID adminId, Booking booking,
                                        AttendanceResult outcome, String reason) {
        Instant now = context.commandNow();
        UUID bookingId = booking.bookingId();
        AttendanceStore.Evidence evidence = store.find(context, bookingId)
                .filter(found -> found.result() != null)
                .orElseThrow(() -> new ApiException(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED));     // not resolved yet
        if (evidence.activeNoShow() != null || evidence.result() == outcome) {
            throw new ApiException(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);                       // FR-ATT-12
        }
        // E7-71: the Admin's Loyalty context first - a Ban the corrected replay activates records its fee decisions in it
        rewards.beginCorrection(context, adminId, booking.customerId());
        if (!store.correct(context, bookingId, outcome, now)) {
            throw new ApiException(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);
        }
        recordCorrection(context, adminId, booking, evidence.result(), outcome, reason, now);
        UUID correction = trust.correctAttendance(context, booking.customerId(), bookingId, outcome);
        rewards.reconcileCorrection(context, adminId, booking.customerId(), bookingId);         // FR-TRS-08
        BookingStatus status = currentStatus(context, bookingId);
        outbox.publish(context, AttendanceEvents.corrected(booking, AttendanceEvents.Correction.ATTENDANCE_CORRECTED,
                evidence.result(), outcome, status, now));
        return new CorrectedAttendance(bookingId, status, evidence.result(), outcome, correction, now);
    }

    private ReversedNoShow reverse(CommandContext context, UUID adminId, Booking booking, AttendanceResult outcome,
                                   boolean serviceLoss, String reason) {
        Instant now = context.commandNow();
        UUID bookingId = booking.bookingId();
        AttendanceStore.Evidence evidence = store.find(context, bookingId)
                .filter(found -> found.activeNoShow() != null)
                .orElseThrow(() -> new ApiException(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED));     // none, or reversed
        rewards.beginCorrection(context, adminId, booking.customerId());                    // E7-71, as correct()
        Reversal reversal = bookings.reverseNoShow(context, bookingId, now, adminId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED));
        UUID reversedEvent = bookings.recordAdminEvent(context, bookingId, now, BookingEventType.NO_SHOW_REVERSED,
                adminId);
        audit.write(context, AuditRecord.builder(AttendanceAuditAction.NO_SHOW_REVERSED, AuditActor.admin(adminId),
                        bookingId, CorrelationId.require())
                .reason(reason)
                .stateChange(BookingStatus.NO_SHOW.name(), reversal.status().name())
                .contextVenue(booking.venueId())
                .build());
        boolean afterEnd = reversal.status() == BookingStatus.COMPLETED;
        // Section 27.8: only the post-endAt default needs no further Attendance Correction
        boolean corrected = !(afterEnd && outcome == AttendanceResult.ATTENDANCE_UNCONFIRMED);
        if (corrected) {
            recordCorrection(context, adminId, booking, evidence.result(), outcome, reason, now);
        }
        if (!store.reverseNoShow(context, bookingId, outcome, now)) {
            throw new ApiException(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);
        }
        UUID reversalEvent = trust.reverseNoShow(context, booking.customerId(), bookingId);
        UUID correctionEvent = corrected
                ? trust.correctAttendance(context, booking.customerId(), bookingId, outcome)
                : null;
        UUID decision = refunds.recordAdminDecision(context, adminId, booking.customerId(), bookingId,
                afterEnd ? reversedEvent : reversal.historyId(), serviceLoss, reason);          // FR-PTS-15
        rewards.reconcileCorrection(context, adminId, booking.customerId(), bookingId);
        // One event for the whole reversal, its further correction included (arch 08 section 3.2: no NoShowReversed,
        // no BookingCancelled / BookingCompleted for it)
        outbox.publish(context, AttendanceEvents.corrected(booking, AttendanceEvents.Correction.NO_SHOW_REVERSED,
                evidence.result(), outcome, reversal.status(), now));
        return new ReversedNoShow(bookingId, reversal.status(), outcome, reversalEvent, correctionEvent, decision, now);
    }

    private AttendanceState reverseCheckIn(CommandContext context, UUID adminId, Booking booking, String reason) {
        Instant now = context.commandNow();
        UUID bookingId = booking.bookingId();
        AttendanceStore.Evidence evidence = store.find(context, bookingId)
                .filter(found -> found.activeCheckIn() != null)
                .orElseThrow(() -> new ApiException(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED));  // none, or reversed
        BookingStatus status = currentStatus(context, bookingId);
        boolean attended = evidence.result() == AttendanceResult.ATTENDED;
        if (evidence.result() == null ? status != BookingStatus.ACTIVE : !attended) {
            throw new ApiException(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);                    // correct it instead
        }
        recordReversal(context, adminId, booking, BookingEventType.CHECK_IN_REVERSED,
                AttendanceAuditAction.CHECK_IN_REVERSED, reason, now);
        if (attended) {
            // arch 07 section 7.4: ATTENDANCE_CORRECTED, and the points reversed by a compensating transaction
            recordCorrection(context, adminId, booking, AttendanceResult.ATTENDED,
                    AttendanceResult.ATTENDANCE_UNCONFIRMED, reason, now);
            if (!store.reverseAttendedCheckIn(context, bookingId, now)) {
                throw new ApiException(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);
            }
            trust.correctAttendance(context, booking.customerId(), bookingId, AttendanceResult.ATTENDANCE_UNCONFIRMED);
            rewards.reconcileCorrection(context, adminId, booking.customerId(), bookingId);     // FR-TRS-08
        } else if (!store.reverseCheckIn(context, bookingId, now)) {
            throw new ApiException(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);
        }
        outbox.publish(context, AttendanceEvents.corrected(booking, AttendanceEvents.Correction.CHECK_IN_REVERSED,
                evidence.result(), attended ? AttendanceResult.ATTENDANCE_UNCONFIRMED : null, status, now));
        return state(context, bookingId);
    }

    private AttendanceState reverseLatePresent(CommandContext context, UUID adminId, Booking booking, String reason) {
        Instant now = context.commandNow();
        UUID bookingId = booking.bookingId();
        AttendanceStore.Evidence evidence = store.find(context, bookingId)
                .filter(found -> found.activeLatePresent() != null)
                .orElseThrow(() -> new ApiException(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED));  // none, or reversed
        BookingStatus status = currentStatus(context, bookingId);
        // arch 07 section 7.4: only before endAt, where it re-opens no-show eligibility
        if (evidence.result() != null || status != BookingStatus.ACTIVE || !now.isBefore(booking.endAt())) {
            throw new ApiException(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);
        }
        recordReversal(context, adminId, booking, BookingEventType.LATE_PRESENT_REVERSED,
                AttendanceAuditAction.LATE_PRESENT_REVERSED, reason, now);
        if (!store.reverseLatePresent(context, bookingId, now)) {
            throw new ApiException(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);
        }
        outbox.publish(context, AttendanceEvents.corrected(booking, AttendanceEvents.Correction.LATE_PRESENT_REVERSED,
                null, null, status, now));
        return state(context, bookingId);
    }

    /** The Admin Booking Event of an evidence reversal and its audit row with the reason, at the reversal instant. */
    private void recordReversal(CommandContext context, UUID adminId, Booking booking, BookingEventType event,
                                AttendanceAuditAction action, String reason, Instant now) {
        bookings.recordAdminEvent(context, booking.bookingId(), now, event, adminId);
        audit.write(context, AuditRecord.builder(action, AuditActor.admin(adminId), booking.bookingId(),
                        CorrelationId.require())
                .reason(reason)
                .contextVenue(booking.venueId())
                .build());
    }

    private AttendanceState state(CommandContext context, UUID bookingId) {
        AttendanceStore.Evidence evidence = store.find(context, bookingId)
                .orElseGet(() -> AttendanceStore.Evidence.none(bookingId));
        return new AttendanceState(bookingId, currentStatus(context, bookingId),
                bookings.terminalReason(context, bookingId).orElse(null), evidence.result(),
                evidence.activeCheckIn(), evidence.activeLatePresent(), evidence.activeNoShow());
    }

    /** The Admin {@code ATTENDANCE_CORRECTED} Booking Event and its audit row with the reason, at the same instant. */
    private void recordCorrection(CommandContext context, UUID adminId, Booking booking, AttendanceResult previous,
                                  AttendanceResult outcome, String reason, Instant now) {
        bookings.recordAdminEvent(context, booking.bookingId(), now, BookingEventType.ATTENDANCE_CORRECTED, adminId);
        audit.write(context, AuditRecord.builder(AttendanceAuditAction.ATTENDANCE_CORRECTED,
                        AuditActor.admin(adminId), booking.bookingId(), CorrelationId.require())
                .reason(reason)
                .stateChange(previous.name(), outcome.name())
                .contextVenue(booking.venueId())
                .build());
    }

    private BookingStatus currentStatus(CommandContext context, UUID bookingId) {
        return bookings.find(context, bookingId)
                .orElseThrow(() -> new IllegalStateException("Booking " + bookingId + " vanished"))
                .status();
    }

    /** An admin attendance command: category B, an existing commitment's operation (ADR-022). */
    private record Body<R>(CommandScope scope, Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }
}
