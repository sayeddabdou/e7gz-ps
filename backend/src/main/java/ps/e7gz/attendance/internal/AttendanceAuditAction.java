package ps.e7gz.attendance.internal;

import ps.e7gz.audit.api.AuditAction;
import ps.e7gz.audit.api.AuditPayloadAllowlist;

/**
 * Audit actions owned by Attendance. The constant name is the persisted {@code audit_event.action}; each matches its
 * Booking Event, as {@code attendance.assert_attendance_action_audit} requires at commit: subject BOOKING, actor the
 * venue account, at the evidence instant (FR-ATT-01: audited with venue account identity, timestamp and Booking ID).
 */
enum AttendanceAuditAction implements AuditAction {

    CHECK_IN_RECORDED,
    LATE_PRESENT_RECORDED,
    NO_SHOW_RECORDED;

    @Override
    public String subjectType() {
        return "BOOKING";
    }

    @Override
    public AuditPayloadAllowlist payloadAllowlist() {
        return AuditPayloadAllowlist.none();
    }
}
