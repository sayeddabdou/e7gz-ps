package ps.e7gz.support.boundary.pending;

import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The check-in window closes 15 minutes after the start (roadmap Phase 3 exit; FRS 4.6, arch 07 section 7). */
@Disabled("E7-59: check-in, Mark Present - Late, no-show (O13)")
class CheckInBoundaryTest {

    @ParameterizedTest
    @ValueSource(strings = {"PT14M59S", "PT15M"})
    void checkInAtFourteenFiftyNineSucceedsAndAtFifteenIsRefusedWithLatePresentOffered(String afterStart) {
        // The command instant at startAt + afterStart (CommandInstants on the rule, since start_at is an immutable
        // snapshot): +14:59 checks in; +15:00 is refused and Mark Present - Late becomes available.
        fail("E7-59: not implemented yet");
    }

    @Test
    void aLatePresentBookingCannotBeMarkedNoShow() {
        fail("E7-59: not implemented yet");
    }
}
