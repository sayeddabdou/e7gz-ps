package ps.e7gz.attendance.internal;

import java.util.Objects;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingSource;

/**
 * The eight attendance-default rules (FR-ATT-08, arch 07 section 7.3; E7-61): the Attendance Result a booking's
 * outcome resolves to from its standing evidence. They are defaults, not permanent formulas - after an audited
 * correction the stored result wins (FR-ATT-09), and nothing here re-derives it. The database holds the same table
 * ({@code attendance.assert_attendance_row}) and refuses a result that breaks it.
 *
 * <table>
 *   <caption>arch 07 section 7.3</caption>
 *   <tr><th>Situation</th><th>Default</th></tr>
 *   <tr><td>1. COMPLETED after a valid check-in</td><td>ATTENDED</td></tr>
 *   <tr><td>2. COMPLETED without verified attendance and without no-show</td><td>ATTENDANCE_UNCONFIRMED</td></tr>
 *   <tr><td>3. status NO_SHOW</td><td>ABSENT_NO_SHOW</td></tr>
 *   <tr><td>4. valid check-in then emergency interruption</td><td>ATTENDED_INTERRUPTED</td></tr>
 *   <tr><td>5. manual booking</td><td>NOT_APPLICABLE</td></tr>
 *   <tr><td>6. cancelled or rejected before attendance</td><td>NOT_APPLICABLE</td></tr>
 *   <tr><td>7. interruption with Late Present only</td><td>ATTENDANCE_UNCONFIRMED</td></tr>
 *   <tr><td>8. interruption with no evidence</td><td>NOT_APPLICABLE</td></tr>
 * </table>
 *
 * E7-61 applies rules 1, 2 and 5 at completion; rule 3 is the no-show's own write (E7-59). Rules 4, 7 and 8 belong to
 * the ACTIVE interruptions (emergencies, owner review, admin cancel; Sprint 5) and rule 6 to the cancellations, which
 * resolve through this same function when they write attendance.
 */
final class AttendanceDefaults {

    /** How the booking ended, as the rules distinguish it. */
    enum Outcome {
        /** ACTIVE -> COMPLETED at {@code endAt} (T5). */
        COMPLETED,
        /** A committed no-show (O13). */
        NO_SHOW,
        /** An ACTIVE booking cancelled by an emergency, owner review or an admin before its end. */
        INTERRUPTED,
        /** Cancelled or rejected before the booking started. */
        CANCELLED_OR_REJECTED
    }

    private AttendanceDefaults() {
    }

    static AttendanceResult resolve(BookingSource source, Outcome outcome, AttendanceStore.Evidence evidence) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(evidence, "evidence");
        if (Objects.requireNonNull(source, "source") == BookingSource.MANUAL) {
            return AttendanceResult.NOT_APPLICABLE;                                                     // rule 5
        }
        boolean checkedIn = evidence.activeCheckIn() != null;
        boolean latePresent = evidence.activeLatePresent() != null;
        return switch (outcome) {
            case COMPLETED -> {
                if (evidence.activeNoShow() != null) {
                    throw new IllegalStateException("A booking with a standing no-show never completes");
                }
                yield checkedIn ? AttendanceResult.ATTENDED                                             // rule 1
                        : AttendanceResult.ATTENDANCE_UNCONFIRMED;                                      // rule 2
            }
            case NO_SHOW -> AttendanceResult.ABSENT_NO_SHOW;                                            // rule 3
            case INTERRUPTED -> checkedIn ? AttendanceResult.ATTENDED_INTERRUPTED                       // rule 4
                    : latePresent ? AttendanceResult.ATTENDANCE_UNCONFIRMED                             // rule 7
                    : AttendanceResult.NOT_APPLICABLE;                                                  // rule 8
            case CANCELLED_OR_REJECTED -> AttendanceResult.NOT_APPLICABLE;                              // rule 6
        };
    }
}
