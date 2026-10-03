package ps.e7gz.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** E7-32: the harness itself - N threads, one common release, bounded waits, structured outcomes. No database. */
class ConcurrentRaceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void noRacerPassesTheStartLineBeforeAllNHaveArrived() {
        int n = 12;
        AtomicInteger arrived = new AtomicInteger();

        ConcurrentRace.Result<Integer> result = ConcurrentRace.run(n, TIMEOUT, (racer, start) -> {
            arrived.incrementAndGet();
            start.await();
            return arrived.get();
        });

        assertThat(result.failures()).isEmpty();
        assertThat(result.values()).as("every racer saw all N arrivals once released").hasSize(n).containsOnly(n);
    }

    @Test
    void allNRacersRunAtTheSameTimeOnNDistinctThreads() {
        int n = 12;
        // Only passable if all N are running at once after the start: a smaller executor would deadlock here.
        CyclicBarrier allRunningTogether = new CyclicBarrier(n);
        Set<String> threads = ConcurrentHashMap.newKeySet();

        ConcurrentRace.Result<String> result = ConcurrentRace.run(n, TIMEOUT, (racer, start) -> {
            start.await();
            threads.add(Thread.currentThread().getName());
            allRunningTogether.await(5, TimeUnit.SECONDS);
            return Thread.currentThread().getName();
        });

        assertThat(result.failures()).isEmpty();
        assertThat(result.threadsUsed()).isEqualTo(n);
        assertThat(threads).hasSize(n);
    }

    @Test
    void outcomesAreReportedPerRacerWithTheRacersOwnException() {
        IllegalStateException boom = new IllegalStateException("odd racer loses");

        ConcurrentRace.Result<Integer> result = ConcurrentRace.run(4, TIMEOUT, (racer, start) -> {
            start.await();
            if (racer % 2 == 1) {
                throw boom;
            }
            return racer;
        });

        assertThat(result.outcomes()).extracting(ConcurrentRace.Outcome::racer).containsExactly(0, 1, 2, 3);
        assertThat(result.values()).containsExactly(0, 2);
        assertThat(result.failures()).containsExactly(boom, boom);
    }

    @Test
    void aRacerThatNeverReachesTheStartLineFailsTheRaceInsteadOfHanging() {
        assertThatThrownBy(() -> ConcurrentRace.run(3, Duration.ofMillis(500), (racer, start) -> {
            if (racer == 0) {
                Thread.sleep(5_000); // stuck before the start line
            }
            start.await();
            return racer;
        })).isInstanceOf(AssertionError.class).hasMessageContaining("reached the start line");
    }

    @Test
    void aRacerThatEndsBeforeTheStartLineFailsTheRaceAtOnce() {
        assertThatThrownBy(() -> ConcurrentRace.run(3, TIMEOUT, (racer, start) -> {
            if (racer == 1) {
                throw new IllegalStateException("never got to the race point");
            }
            start.await();
            return racer;
        })).isInstanceOf(AssertionError.class).hasMessageContaining("1 of 3 racers ended without reaching");
    }

    @Test
    void aRaceNeedsAtLeastTwoRacers() {
        assertThatThrownBy(() -> ConcurrentRace.run(1, TIMEOUT, (racer, start) -> racer))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
