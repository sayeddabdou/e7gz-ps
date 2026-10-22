package ps.e7gz.support.boundary.pending;

import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ps.e7gz.support.boundary.Boundary;

/**
 * T6 {@code VENUE_RESPONSE_EXPIRED} resolution, due at {@code booking.start_at} (arch 08 section 1; FR-BOK-23). Its T4
 * twin moved next to E7-60's code ({@code ps.e7gz.booking.internal.BookingStartBoundaryTest}); T6 has no handler and
 * no task that schedules it yet - open question for the Product Owner, raised by E7-60.
 */
@Disabled("T6: VENUE_RESPONSE_EXPIRED resolution at start - no owning task yet (raised by E7-60)")
class BookingStartBoundaryTest {

    @ParameterizedTest
    @EnumSource(Boundary.class)
    void t6ResolvesAnExpiredRequestAtItsOriginalStartAndNotASecondBefore(Boundary side) {
        fail("T6: not implemented yet");
    }
}
