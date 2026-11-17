package ps.e7gz.attendance.internal;

import java.util.UUID;
import org.springframework.context.ApplicationContext;
import ps.e7gz.common.codes.AttendanceResult;

/**
 * Test support for other modules (E7-59): the venue's three attendance actions - and the Platform Admin's correction and
 * no-show reversal (E7-70) - through the real command, without an Idempotency-Key record, so a consumer of their
 * integration events is tested against what Attendance really writes. It adds no behaviour; it only reaches the
 * package-private entry points this package's tests use.
 */
public final class AttendanceFlows {

    private final Attendances attendances;
    private final AttendanceCorrections corrections;

    private AttendanceFlows(Attendances attendances, AttendanceCorrections corrections) {
        this.attendances = attendances;
        this.corrections = corrections;
    }

    public static AttendanceFlows of(ApplicationContext context) {
        return new AttendanceFlows(context.getBean(Attendances.class), context.getBean(AttendanceCorrections.class));
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

    /** E7-70: the Platform Admin's Attendance correction through the real command. */
    public CorrectedAttendance correct(UUID adminId, UUID bookingId, AttendanceResult outcome, String reason) {
        return corrections.correct(adminId, bookingId, outcome, reason);
    }

    /** E7-70: the Platform Admin's no-show reversal through the real command. */
    public ReversedNoShow reverseNoShow(UUID adminId, UUID bookingId, AttendanceResult outcome, boolean serviceLoss,
                                        String reason) {
        return corrections.reverseNoShow(adminId, bookingId, outcome, serviceLoss, reason);
    }
}
