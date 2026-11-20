package ps.e7gz.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import ps.e7gz.venue.api.OperatingSessions.SessionPeriod;

/**
 * E7-83: the optimistic feasibility test and the online intake window of a paused response timer (FR-OPS-39,
 * FR-OPS-40, FR-BOK-09; arch 08 section 2.5), as a pure rule - no database, no clock.
 */
class TimerFeasibilityTest {

    static final Instant NOW = Instant.parse("2026-12-10T16:00:00Z");
    static final Duration SEVEN = Duration.ofMinutes(7);

    static SessionPeriod session(Instant start, Instant end) {
        return new SessionPeriod(LocalDate.of(2026, 12, 10), start, end);
    }

    static Instant at(int minutesFromNow) {
        return NOW.plus(Duration.ofMinutes(minutesFromNow));
    }

    @Test
    void aWindowLongEnoughBeforeTheStartIsFeasibleUntilTheLastInstantTheRemainingTimeStillFits() {
        // session [-2h, +10h), start in 60 min: the window ends at the start, so 7 minutes fit until start - 7
        List<SessionPeriod> sessions = List.of(session(at(-120), at(600)));

        assertThat(TimerFeasibility.lastFeasibleResume(NOW, SEVEN, at(60), sessions)).contains(at(53));
        assertThat(TimerFeasibility.feasible(at(53), SEVEN, at(60), sessions)).as("exactly fits: >=").isTrue();
        assertThat(TimerFeasibility.feasible(at(53).plusSeconds(1), SEVEN, at(60), sessions)).isFalse();
    }

    @Test
    void theFinalTenMinutesOfASessionTakeNoRequestsSoTheyAreNotPartOfAnyWindow() {
        // FR-BOK-09: a session ending in 15 minutes leaves a 5-minute window, too short for 7 minutes
        List<SessionPeriod> sessions = List.of(session(at(-120), at(15)));

        assertThat(TimerFeasibility.lastFeasibleResume(NOW, SEVEN, at(60), sessions)).isEmpty();
        assertThat(TimerFeasibility.feasible(NOW, Duration.ofMinutes(5), at(60), sessions)).isTrue();
    }

    @Test
    void aLaterSessionBeforeTheStartCountsAndOneStartingAtOrAfterItDoesNot() {
        List<SessionPeriod> closedNow = List.of(session(at(-120), at(5)), session(at(30), at(600)));
        assertThat(TimerFeasibility.lastFeasibleResume(NOW, SEVEN, at(60), closedNow)).contains(at(53));

        List<SessionPeriod> opensAtTheStart = List.of(session(at(-120), at(5)), session(at(60), at(600)));
        assertThat(TimerFeasibility.lastFeasibleResume(NOW, SEVEN, at(60), opensAtTheStart)).isEmpty();
    }

    @Test
    void noRemainingTimeNeedsOnlyAnOpenWindow() {
        assertThat(TimerFeasibility.lastFeasibleResume(NOW, Duration.ZERO, at(60),
                List.of(session(at(-120), at(600))))).contains(at(60));
        assertThat(TimerFeasibility.lastFeasibleResume(NOW, Duration.ZERO, at(60), List.of())).isEmpty();
    }

    /** FR-OPS-40, PRD Phase 5 exit criterion: identical state, identical decision, every time. */
    @Test
    void feasibilityOnIdenticalStateIsTheSameDecisionEveryTime() {
        List<SessionPeriod> sessions = List.of(session(at(-120), at(20)), session(at(40), at(300)));
        Optional<Instant> first = TimerFeasibility.lastFeasibleResume(NOW, SEVEN, at(90), sessions);
        assertThat(IntStream.range(0, 1_000)
                .mapToObj(i -> TimerFeasibility.lastFeasibleResume(NOW, SEVEN, at(90), List.copyOf(sessions)))
                .distinct().toList()).containsExactly(first);
        assertThat(first).contains(at(83));

        List<SessionPeriod> tooShort = List.of(session(at(-120), at(15)));
        assertThat(IntStream.range(0, 1_000)
                .mapToObj(i -> TimerFeasibility.feasible(NOW, SEVEN, at(90), tooShort))
                .distinct().toList()).containsExactly(false);
    }

    @Test
    void theIntakeWindowIsInsideASessionAndBeforeItsLastTenMinutes() {
        List<SessionPeriod> sessions = List.of(session(at(-120), at(15)), session(at(30), at(600)));

        assertThat(TimerFeasibility.insideIntakeWindow(NOW, sessions)).isTrue();
        assertThat(TimerFeasibility.insideIntakeWindow(at(5), sessions)).as("cut-off reached").isFalse();
        assertThat(TimerFeasibility.insideIntakeWindow(at(20), sessions)).as("closed").isFalse();
        assertThat(TimerFeasibility.nextIntakeWindow(at(5), sessions)).contains(at(30));
        assertThat(TimerFeasibility.nextIntakeWindow(at(30), sessions)).as("already open").isEmpty();
    }
}
