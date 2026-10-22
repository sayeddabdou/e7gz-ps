package ps.e7gz.attendance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ps.e7gz.attendance.internal.AttendanceDefaults.Outcome;
import ps.e7gz.attendance.internal.AttendanceStore.Evidence;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingSource;

/** The eight attendance-default rules of arch 07 section 7.3 (FR-ATT-08), one assertion each (E7-61). */
class AttendanceDefaultsTest {

    private static final UUID BOOKING = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-10-12T18:05:00Z");
    private static final Instant LATER = AT.plusSeconds(600);

    private static final Evidence NONE = Evidence.none(BOOKING);
    private static final Evidence CHECKED_IN = new Evidence(BOOKING, null, AT, null, null, null, null, null);
    private static final Evidence LATE_PRESENT = new Evidence(BOOKING, null, null, null, AT, null, null, null);
    private static final Evidence NO_SHOW = new Evidence(BOOKING, AttendanceResult.ABSENT_NO_SHOW, null, null, null,
            null, AT, null);

    @Test
    void rule1CompletedAfterAValidCheckInIsAttended() {
        assertThat(online(Outcome.COMPLETED, CHECKED_IN)).isEqualTo(AttendanceResult.ATTENDED);
    }

    @Test
    void rule2CompletedWithoutVerifiedAttendanceIsUnconfirmed() {
        assertThat(online(Outcome.COMPLETED, NONE)).isEqualTo(AttendanceResult.ATTENDANCE_UNCONFIRMED);
        assertThat(online(Outcome.COMPLETED, LATE_PRESENT)).as("Late Present is not verified attendance (FR-ATT-03)")
                .isEqualTo(AttendanceResult.ATTENDANCE_UNCONFIRMED);
        Evidence reversedCheckIn = new Evidence(BOOKING, null, AT, LATER, null, null, null, null);
        assertThat(online(Outcome.COMPLETED, reversedCheckIn)).as("an admin-reversed check-in is not valid")
                .isEqualTo(AttendanceResult.ATTENDANCE_UNCONFIRMED);
    }

    @Test
    void rule3ANoShowIsAbsentNoShow() {
        assertThat(online(Outcome.NO_SHOW, NO_SHOW)).isEqualTo(AttendanceResult.ABSENT_NO_SHOW);
    }

    @Test
    void rule4AValidCheckInThenAnInterruptionIsAttendedInterrupted() {
        assertThat(online(Outcome.INTERRUPTED, CHECKED_IN)).isEqualTo(AttendanceResult.ATTENDED_INTERRUPTED);
    }

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void rule5AManualBookingIsAlwaysNotApplicable(Outcome outcome) {
        assertThat(AttendanceDefaults.resolve(BookingSource.MANUAL, outcome, NONE))
                .isEqualTo(AttendanceResult.NOT_APPLICABLE);
    }

    @Test
    void rule6CancelledOrRejectedBeforeAttendanceIsNotApplicable() {
        assertThat(online(Outcome.CANCELLED_OR_REJECTED, NONE)).isEqualTo(AttendanceResult.NOT_APPLICABLE);
    }

    @Test
    void rule7AnInterruptionWithLatePresentOnlyIsUnconfirmed() {
        assertThat(online(Outcome.INTERRUPTED, LATE_PRESENT)).isEqualTo(AttendanceResult.ATTENDANCE_UNCONFIRMED);
    }

    @Test
    void rule8AnInterruptionWithNoEvidenceIsNotApplicable() {
        assertThat(online(Outcome.INTERRUPTED, NONE)).isEqualTo(AttendanceResult.NOT_APPLICABLE);
    }

    @Test
    void aBookingWithAStandingNoShowNeverCompletes() {
        assertThatThrownBy(() -> online(Outcome.COMPLETED, NO_SHOW)).isInstanceOf(IllegalStateException.class);
    }

    private static AttendanceResult online(Outcome outcome, Evidence evidence) {
        return AttendanceDefaults.resolve(BookingSource.ONLINE, outcome, evidence);
    }
}
