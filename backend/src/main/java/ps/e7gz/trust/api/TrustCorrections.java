package ps.e7gz.trust.api;

import java.util.UUID;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.command.CommandContext;

/**
 * The two Trust correction inputs (arch 07 sections 2.1 and 3.3, FR-TRS-07, FR-TRS-09; E7-70), appended on a Platform
 * Admin command that also writes the matching attendance, Booking History and audit rows. A correction never deletes:
 * it targets the booking's one original canonical outcome, inherits its {@code effectiveAt} ({@code booking.startAt},
 * FR-TRS-05), and replay neutralises the target and substitutes the corrected effect. Each call then reconciles the
 * Customer ({@link TrustReconciler}), so the projection and every newly derived consequence are settled in the same
 * transaction. The database checks the chain ({@code trust.validate_attendance_correction_event}): one original, one
 * non-superseded leaf, strictly later sequences.
 */
public interface TrustCorrections {

    /**
     * Appends {@code ATTENDANCE_CORRECTED} with {@code correctedOutcome} (ATTENDED, ATTENDED_INTERRUPTED,
     * ATTENDANCE_UNCONFIRMED or ABSENT_NO_SHOW), superseding the booking's current non-superseded correction if there is
     * one - so any chain of corrections yields exactly one effective outcome (FR-TRS-09). Returns the new event id.
     *
     * @throws IllegalStateException when the booking has no original canonical outcome
     */
    UUID correctAttendance(CommandContext command, UUID customerId, UUID bookingId, AttendanceResult correctedOutcome);

    /**
     * Appends {@code NO_SHOW_REVERSED} targeting the booking's original {@code BOOKING_NO_SHOW} (FR-ATT-12): replay then
     * contributes {@code BOOKING_ATTENDANCE_UNCONFIRMED} at the no-show's own position - no penalty, no reward, no
     * reset - unless a later {@link #correctAttendance} in the chain substitutes another outcome. Returns the event id.
     * The database allows one per booking ({@code ux_trust_booking_no_show_reversed}).
     *
     * @throws IllegalStateException when the booking's original outcome is not a {@code BOOKING_NO_SHOW}
     */
    UUID reverseNoShow(CommandContext command, UUID customerId, UUID bookingId);
}
