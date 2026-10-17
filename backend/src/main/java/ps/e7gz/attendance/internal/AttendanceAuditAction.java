package ps.e7gz.attendance.internal;

import ps.e7gz.audit.api.AuditAction;
import ps.e7gz.audit.api.AuditPayloadAllowlist;

/**
 * Audit actions owned by Attendance. The constant name is the persisted {@code audit_event.action}; each matches its
 * Booking Event, as {@code attendance.assert_attendance_action_audit} requires at commit: subject BOOKING, actor the
 * venue account, at the evidence instant (FR-ATT-01: audited with venue account identity, timestamp and Booking ID).
 * {@code NO_SHOW_REVERSED} and {@code ATTENDANCE_CORRECTED} (E7-70) are the Platform Admin's, with the mandatory reason,
 * at the reversal or correction instant.
 */
enum AttendanceAuditAction implements AuditAction {

    CHECK_IN_RECORDED,
    LATE_PRESENT_RECORDED,
    NO_SHOW_RECORDED,
    NO_SHOW_REVERSED,
    ATTENDANCE_CORRECTED;

    @Override
    public String subjectType() {
        return "BOOKING";
    }

    @Override
    public AuditPayloadAllowlist payloadAllowlist() {
        return AuditPayloadAllowlist.none();
    }

    /** FR-ATT-11, FR-AUD-06: an Admin reversal or correction is refused without its reason. */
    @Override
    public boolean requiresReason() {
        return this == NO_SHOW_REVERSED || this == ATTENDANCE_CORRECTED;
    }
}
