package ps.e7gz.support.boundary.pending;

import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ps.e7gz.support.boundary.Boundary;

/**
 * T2 Late Confirm expiry, due {@code MIN(initiation + 5 min, booking.startAt)}, and T3 its warning, created only when
 * the window is longer than 60 s (arch 08 sections 1, 2.6 and 9; FR-LCF-09, FR-LCF-12; roadmap Phase 3 exit).
 */
@Disabled("E7-58: Late Confirm (O3)")
class LateConfirmBoundaryTest {

    @Test
    void initiatedAt759ForAnEightPmStartTheWindowIsExactlySixtySecondsWithNoWarning() {
        // startAt 20:00, initiation 19:59:00 -> expires 20:00:00 (startAt is the earlier), exactly 60 s;
        // no T3 job is written and no warning notification is ever sent.
        fail("E7-58: not implemented yet");
    }

    @Test
    void initiatedAt750TheWindowIsFiveMinutesWithOneWarningAboutAMinuteBefore() {
        // startAt 20:00, initiation 19:50:00 -> expires 19:55:00; exactly one T3 job, due about 19:54:00
        // (BoundaryAssertions.assertWithinOneSecond), and one warning notification when it fires.
        fail("E7-58: not implemented yet");
    }

    @ParameterizedTest
    @EnumSource(Boundary.class)
    void theCustomerAcceptsOnlyBeforeTheLateConfirmExpiry(Boundary side) {
        // Deadlines.moveTo(command, side, <the late confirmation's expiry column>, id), then the customer accepts in
        // the same command: accepted only one second before (BoundaryAssertions.assertBoundaryRule).
        fail("E7-58: not implemented yet");
    }

    @ParameterizedTest
    @EnumSource(Boundary.class)
    void t2ExpiresTheLateConfirmAtItsDeadlineAndNotASecondBefore(Boundary side) {
        // DueJobs.of(context, <T2 handler>).fireAt(scope, <T2 key>, side, <move the expiry>): one second before, the
        // job is rescheduled to the deadline and nothing changes; at and after, it expires as of the deadline.
        fail("E7-58: not implemented yet");
    }

    @Test
    void aCustomerAtFourCommitmentsIsBlockedWithNoHoldAndTheVenueLearnsNothing() {
        // Initiation for a customer holding 4 commitments is refused; no reservation hold is written; the venue sees
        // only the neutral failure (arch 10 section 5.2).
        fail("E7-58: not implemented yet");
    }
}
