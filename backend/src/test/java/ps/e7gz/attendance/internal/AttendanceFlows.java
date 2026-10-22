package ps.e7gz.attendance.internal;

import java.util.UUID;
import org.springframework.context.ApplicationContext;

/**
 * Test support for other modules (E7-59): the venue's three attendance actions through the real command, without an
 * Idempotency-Key record, so a consumer of their integration events is tested against what Attendance really writes.
 * It adds no behaviour; it only reaches the package-private entry point this package's tests use.
 */
public final class AttendanceFlows {

    private final Attendances attendances;

    private AttendanceFlows(Attendances attendances) {
        this.attendances = attendances;
    }

    public static AttendanceFlows of(ApplicationContext context) {
        return new AttendanceFlows(context.getBean(Attendances.class));
    }

    public RecordedAttendance checkIn(UUID venueAccountId, UUID bookingId) {
        return attendances.record(AttendanceAction.CHECK_IN, venueAccountId, bookingId);
    }

    public RecordedAttendance latePresent(UUID venueAccountId, UUID bookingId) {
        return attendances.record(AttendanceAction.LATE_PRESENT, venueAccountId, bookingId);
    }

    public RecordedAttendance noShow(UUID venueAccountId, UUID bookingId) {
        return attendances.record(AttendanceAction.NO_SHOW, venueAccountId, bookingId);
    }
}
