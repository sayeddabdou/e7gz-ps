package ps.e7gz.support.boundary.pending;

import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** Roadmap Phase 2 exit: a manual booking outside opening hours carries the business date it belongs to (O4). */
@Disabled("E7-48: manual booking (O4)")
class ManualBookingBusinessDateBoundaryTest {

    @Test
    void aManualBookingAtThreeAmForAVenueClosingAtTwoAmSucceedsWithABusinessDate() {
        // A venue whose session runs 14:00-02:00 (Africa/Cairo); the venue adds a walk-in at 03:00 the next morning.
        // Then: the booking is created, and its businessDate is the date the 14:00 session opened on.
        fail("E7-48: not implemented yet");
    }
}
