package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Attendance result of a booking - {@code ck_attendance_result_values}. */
@Schema(enumAsRef = true)
public enum AttendanceResult {
    ATTENDED,
    ATTENDANCE_UNCONFIRMED,
    ATTENDED_INTERRUPTED,
    ABSENT_NO_SHOW,
    NOT_APPLICABLE
}
