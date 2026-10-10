package ps.e7gz.support.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

/** Assertions of the boundary pack: arch 08 section 8 promises boundary correctness at one second (EC-X-002). */
public final class BoundaryAssertions {

    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    private BoundaryAssertions() {
    }

    /** {@code actual} lies within one second of {@code expected}, either way. */
    public static void assertWithinOneSecond(Instant actual, Instant expected) {
        assertThat(actual).as("within one second of %s", expected)
                .isBetween(expected.minus(ONE_SECOND), expected.plus(ONE_SECOND));
    }

    /**
     * The boundary rule (FR-AVL-15): an action tried on {@code side} of its deadline was permitted exactly when
     * {@code now < deadline}.
     */
    public static void assertBoundaryRule(Boundary side, boolean permitted) {
        assertThat(permitted).as("now %s the deadline: permitted only before it", side)
                .isEqualTo(side.beforeDeadline());
    }
}
