package ps.e7gz.attendance.internal;

import ps.e7gz.common.codes.BookingEventType;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.messaging.api.IntegrationEvents;

/**
 * The venue's three attendance actions (arch 07 section 7.1, FRS section 4.6), each with the 422 it answers outside
 * its window or with blocking evidence - also for a Manual Booking, which has none of them (FR-MAN-04; Sprint 3
 * contract) - its Booking Event and audit action, and its integration event.
 */
enum AttendanceAction {

    /** FR-ATT-01, FR-ATT-02: {@code [startAt, startAt + 15 min)}. */
    CHECK_IN(ApiErrorCode.CHECK_IN_WINDOW_CLOSED, BookingEventType.CHECK_IN_RECORDED,
            AttendanceAuditAction.CHECK_IN_RECORDED, IntegrationEvents.CHECK_IN_RECORDED),

    /** FR-ATT-03: {@code [startAt + 15 min, endAt)}, no valid check-in, no committed no-show. */
    LATE_PRESENT(ApiErrorCode.LATE_PRESENT_NOT_AVAILABLE, BookingEventType.LATE_PRESENT_RECORDED,
            AttendanceAuditAction.LATE_PRESENT_RECORDED, IntegrationEvents.LATE_PRESENT_RECORDED),

    /** FR-ATT-04, FR-ATT-05: {@code [startAt + 15 min, endAt)}, no valid check-in, no Mark Present - Late. */
    NO_SHOW(ApiErrorCode.NO_SHOW_NOT_AVAILABLE, BookingEventType.NO_SHOW_RECORDED,
            AttendanceAuditAction.NO_SHOW_RECORDED, IntegrationEvents.NO_SHOW_RECORDED);

    private final ApiErrorCode refusal;
    private final BookingEventType bookingEvent;
    private final AttendanceAuditAction auditAction;
    private final String integrationEvent;

    AttendanceAction(ApiErrorCode refusal, BookingEventType bookingEvent, AttendanceAuditAction auditAction,
                     String integrationEvent) {
        this.refusal = refusal;
        this.bookingEvent = bookingEvent;
        this.auditAction = auditAction;
        this.integrationEvent = integrationEvent;
    }

    ApiErrorCode refusal() {
        return refusal;
    }

    BookingEventType bookingEvent() {
        return bookingEvent;
    }

    AttendanceAuditAction auditAction() {
        return auditAction;
    }

    String integrationEvent() {
        return integrationEvent;
    }
}
