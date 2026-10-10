package ps.e7gz.support.boundary.pending;

import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** Emergency pause and resume of a response timer (arch 08 sections 2.5 and 9; FR-OPS-34...41). */
@Disabled("E7-83: timer pause/resume and feasibility")
class TimerPauseResumeBoundaryTest {

    @Test
    void aTenMinuteTimerPausedAfterThreeMinutesResumesWithSevenMinutesLeft() {
        // FR-OPS-36: T1 re-armed at resume + 7 min, to the second (DueJobs.job(key).dueAt()).
        fail("E7-83: not implemented yet");
    }

    @Test
    void twelveMinutesVenuePauseThenNineteenResourcePauseCancelsAtThirtyUnderTheResourceReason() {
        // FR-OPS-37, FR-OPS-38: the 30-minute limit is reached during the resource pause, so the reason is the
        // resource's.
        fail("E7-83: not implemented yet");
    }

    @Test
    void anEmergencyEndingWhileTheVenueIsClosedDoesNotRestartTheTimer() {
        // FR-OPS-39: the timer waits for the next valid intake window.
        fail("E7-83: not implemented yet");
    }

    @Test
    void feasibilityOnIdenticalStateIsTheSameDecisionEveryTime() {
        // FR-OPS-40.
        fail("E7-83: not implemented yet");
    }
}
