package ps.e7gz.attendance.internal;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.AttendanceBookings.Booking;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.trust.api.BookingOutcomes;

/**
 * Platform Attendance Protection at attendance resolution (arch 07 section 7.5, FR-ATT-16..22; E7-78). Called by every
 * resolver of an ACTIVE booking's attendance - completion ({@link JdbcCompletionAttendance}) and early termination
 * ({@link JdbcInterruptionAttendance}, FR-ATT-18) - right after the result is stored, on the same command:
 * <ul>
 *   <li>only an Online Booking whose result is ATTENDANCE_UNCONFIRMED or NOT_APPLICABLE (condition 6; FR-ATT-22: an
 *       ATTENDED or ATTENDED_INTERRUPTED one already reset the counter, and ABSENT_NO_SHOW is authoritative);</li>
 *   <li>with a candidate recorded at disable time on an interval that started during the session, before any
 *       evidence, and overlapped the window in which the missing evidence would have been recorded - the check-in and
 *       no-show windows (FR-ATT-17); a standing Late Present after the re-enable needs the disable to have overlapped
 *       the check-in window (FR-ATT-23);</li>
 *   <li>then one {@code BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION} - {@code effectiveAt = startAt},
 *       {@code occurredAt} = the resolution instant (FR-ATT-19) - and {@code protection_applied} on the record. At
 *       most one per booking, however many intervals it spans (FR-ATT-21; {@code ux_trust_booking_account_disable_protection}).</li>
 * </ul>
 * {@link #QUALIFYING} is the decision; {@code attendance.assert_protection_trust_event} checks the same conditions and
 * refuses a wrong write. Its replay effect - reset the consecutive no-show counter and nothing else, neutralised by an
 * Admin correction to ABSENT_NO_SHOW (FR-ATT-19, FR-ATT-24) - is the database's ({@code trust} replay).
 */
@Component
class AttendanceProtection {

    /** Whether a candidate qualifies, at the resolution just stored ({@code resolved_at}). */
    static final String QUALIFYING = """
            SELECT EXISTS (
                SELECT 1
                  FROM attendance.attendance_protection_candidate c
                  JOIN iam.venue_account_restriction_interval r ON r.id = c.restriction_interval_id
                  JOIN booking.booking b ON b.id = c.booking_id
                  JOIN attendance.attendance_record a ON a.booking_id = c.booking_id
                 WHERE c.booking_id = :bookingId
                   AND r.venue_id = b.venue_id
                   AND c.recorded_at = r.disabled_at
                   AND r.disabled_at >= b.start_at
                   AND r.disabled_at < LEAST(b.end_at, a.resolved_at)
                   AND COALESCE(r.reenabled_at, 'infinity'::timestamptz) > b.start_at
                   AND (a.check_in_at IS NULL OR a.check_in_at >= r.disabled_at)
                   AND (a.late_present_at IS NULL OR a.late_present_at >= r.disabled_at)
                   AND (a.no_show_at IS NULL OR a.no_show_at >= r.disabled_at)
                   AND (a.late_present_at IS NULL OR a.late_present_reversed_at IS NOT NULL
                        OR r.disabled_at < LEAST(b.start_at + interval '15 minutes', b.end_at)))
            """;

    static final String MARK_APPLIED = """
            UPDATE attendance.attendance_record
               SET protection_applied = true
             WHERE booking_id = :bookingId AND protection_applied = false
            """;

    private final BookingOutcomes trust;

    AttendanceProtection(BookingOutcomes trust) {
        this.trust = trust;
    }

    /**
     * Applies protection when the booking qualifies.
     *
     * @return whether the protection event was written
     */
    boolean applyAtResolution(CommandContext command, Booking booking, AttendanceResult result) {
        Objects.requireNonNull(booking, "booking");
        if (booking.source() != BookingSource.ONLINE || (result != AttendanceResult.ATTENDANCE_UNCONFIRMED
                && result != AttendanceResult.NOT_APPLICABLE)) {
            return false;
        }
        UUID bookingId = booking.bookingId();
        boolean qualifies = Boolean.TRUE.equals(command.jdbc().sql(QUALIFYING).param("bookingId", bookingId)
                .query(Boolean.class).single());
        if (!qualifies) {
            return false;
        }
        trust.recordAttendanceProtection(command, booking.customerId(), bookingId, booking.startAt());
        if (command.jdbc().sql(MARK_APPLIED).param("bookingId", bookingId).update() != 1) {
            throw new IllegalStateException("Attendance of booking " + bookingId + " was protected already");
        }
        return true;
    }
}
