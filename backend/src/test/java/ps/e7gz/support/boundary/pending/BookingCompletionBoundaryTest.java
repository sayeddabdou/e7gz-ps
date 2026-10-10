package ps.e7gz.support.boundary.pending;

import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ps.e7gz.support.boundary.Boundary;

/** T5 booking completion, due at {@code booking.end_at} (arch 08 section 1; FR-BOK-24; arch 07 section 7.3). */
@Disabled("E7-61: completion and attendance defaults (O12)")
class BookingCompletionBoundaryTest {

    @ParameterizedTest
    @EnumSource(Boundary.class)
    void t5CompletesTheBookingAtItsEndAndNotASecondBefore(Boundary side) {
        // The handler on CommandInstants.at(side, endAt) (end_at is an immutable snapshot): one second before, it
        // reschedules to end_at; at and after, it completes as of end_at with its attendance default - a Late-Present
        // booking completes as ATTENDANCE_UNCONFIRMED with no points.
        fail("E7-61: not implemented yet");
    }
}
