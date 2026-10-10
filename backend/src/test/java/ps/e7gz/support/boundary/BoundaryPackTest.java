package ps.e7gz.support.boundary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The boundary pack's own rules (E7-45), without a database. */
class BoundaryPackTest {

    private static final Instant DEADLINE = Instant.parse("2026-10-12T18:10:00Z");

    @Test
    void theThreeSidesAreOneSecondBeforeAtAndOneSecondAfter() {
        assertThat(Boundary.ONE_SECOND_BEFORE.now(DEADLINE)).isEqualTo("2026-10-12T18:09:59Z");
        assertThat(Boundary.AT.now(DEADLINE)).isEqualTo(DEADLINE);
        assertThat(Boundary.ONE_SECOND_AFTER.now(DEADLINE)).isEqualTo("2026-10-12T18:10:01Z");
    }

    @ParameterizedTest
    @EnumSource(Boundary.class)
    void theDeadlineStoredPutsNowOnThatSide(Boundary side) {
        Instant now = Instant.parse("2026-10-12T18:00:00.123456Z");
        assertThat(side.now(side.deadline(now))).isEqualTo(now);
        assertThat(now.isBefore(side.deadline(now))).isEqualTo(side.beforeDeadline());
    }

    @Test
    void onlyOneSecondBeforeIsStillPermitted() {
        // FR-AVL-15: now < deadline permits, now >= deadline expires
        assertThat(Boundary.ONE_SECOND_BEFORE.beforeDeadline()).isTrue();
        assertThat(Boundary.AT.beforeDeadline()).isFalse();
        assertThat(Boundary.ONE_SECOND_AFTER.beforeDeadline()).isFalse();
    }

    @Test
    void theBoundaryRuleAssertionFailsWhenTheDeadlineIsMissedEitherWay() {
        BoundaryAssertions.assertBoundaryRule(Boundary.ONE_SECOND_BEFORE, true);
        BoundaryAssertions.assertBoundaryRule(Boundary.AT, false);
        assertThatThrownBy(() -> BoundaryAssertions.assertBoundaryRule(Boundary.AT, true))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> BoundaryAssertions.assertBoundaryRule(Boundary.ONE_SECOND_BEFORE, false))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void withinOneSecondIsInclusiveBothWays() {
        BoundaryAssertions.assertWithinOneSecond(DEADLINE.plusSeconds(1), DEADLINE);
        BoundaryAssertions.assertWithinOneSecond(DEADLINE.minusSeconds(1), DEADLINE);
        assertThatThrownBy(() -> BoundaryAssertions.assertWithinOneSecond(DEADLINE.plusMillis(1001), DEADLINE))
                .isInstanceOf(AssertionError.class);
    }

    @ParameterizedTest
    @EnumSource(Boundary.class)
    void aCommandContextAtAnExactInstant(Boundary side) {
        assertThat(CommandInstants.at(side, DEADLINE).commandNow()).isEqualTo(side.now(DEADLINE));
        assertThat(CommandInstants.at(DEADLINE).jdbc()).isNull();
    }

    @Test
    void theTestClockIsSetAndMovedByHand() {
        TestClock clock = new TestClock(DEADLINE);
        clock.advance(Duration.ofMinutes(5));
        assertThat(Instant.now(clock)).isEqualTo("2026-10-12T18:15:00Z");
        clock.set(DEADLINE);
        assertThat(clock.instant()).isEqualTo(DEADLINE);
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThatThrownBy(() -> clock.withZone(ZoneId.of("Africa/Cairo")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aDeadlineColumnIsSchemaTableColumnOnly() {
        assertThatThrownBy(() -> Deadlines.move(CommandInstants.at(DEADLINE), "booking.booking; DROP TABLE x",
                UUID.randomUUID(), DEADLINE)).isInstanceOf(IllegalArgumentException.class);
    }
}
