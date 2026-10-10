package ps.e7gz.support.boundary.pending;

import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ps.e7gz.support.boundary.Boundary;

/**
 * T4 booking start and T6 {@code VENUE_RESPONSE_EXPIRED} resolution, both due at {@code booking.start_at} (arch 08
 * section 1; FR-BOK-23, FR-BOK-24; arch 05 section 7).
 */
@Disabled("E7-60: start-time revalidation (O11)")
class BookingStartBoundaryTest {

    @ParameterizedTest
    @EnumSource(Boundary.class)
    void t4RevalidatesAtTheStartAndNotASecondBefore(Boundary side) {
        // The handler on CommandInstants.at(side, startAt) (start_at is an immutable snapshot): one second before, it
        // reschedules to start_at; at and after, it revalidates as of start_at with the seven-level reason priority.
        fail("E7-60: not implemented yet");
    }

    @ParameterizedTest
    @EnumSource(Boundary.class)
    void t6ResolvesAnExpiredRequestAtItsOriginalStartAndNotASecondBefore(Boundary side) {
        fail("E7-60: not implemented yet");
    }
}
