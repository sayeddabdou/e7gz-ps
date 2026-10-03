package ps.e7gz.concurrency;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * E7-32 concurrency harness (arch 14 section 10 item 3): runs one racing action on N threads at once and returns
 * every racer's outcome for assertions.
 *
 * <ul>
 *   <li>Exactly N worker threads, one per racer: never a smaller executor, so no racer waits for another to finish.</li>
 *   <li>Each racer calls {@link StartLine#await()} at its race point - for a database race, inside its own open
 *       command transaction. The harness waits (bounded) until all N are there, then releases them with one signal.
 *       No sleeps, no reliance on scheduling luck.</li>
 *   <li>Every wait is bounded by the race timeout, so a broken racer fails the test instead of hanging CI.</li>
 * </ul>
 *
 * <p>The harness knows nothing about databases: the action decides what it races (for example one
 * {@code CommandRunner} command each, so each racer has its own transaction and connection).
 */
public final class ConcurrentRace {

    /** One racer's action. {@code start.await()} must be called once at the race point. */
    @FunctionalInterface
    public interface Racer<T> {

        T run(int racer, StartLine start) throws Exception;
    }

    /** The common start line every racer waits at. */
    public interface StartLine {

        /**
         * Marks this racer as ready and blocks until every racer is ready and the start signal is given. Calling it
         * again in the same racer (for example on a command retry) returns at once once the race has started.
         */
        void await();
    }

    /** What one racer returned or threw. */
    public sealed interface Outcome<T> {

        int racer();

        /** The racer returned normally. */
        record Won<T>(int racer, T value) implements Outcome<T> {
        }

        /** The racer threw; {@code failure} is the racer's own exception, unwrapped. */
        record Lost<T>(int racer, Throwable failure) implements Outcome<T> {
        }
    }

    /** All N outcomes, ordered by racer index. */
    public record Result<T>(int racers, int threadsUsed, List<Outcome<T>> outcomes) {

        public Result {
            outcomes = List.copyOf(outcomes);
        }

        public List<T> values() {
            return outcomes.stream().filter(o -> o instanceof Outcome.Won<T>).map(o -> ((Outcome.Won<T>) o).value())
                    .toList();
        }

        public List<Throwable> failures() {
            return outcomes.stream().filter(o -> o instanceof Outcome.Lost<T>)
                    .map(o -> ((Outcome.Lost<T>) o).failure()).toList();
        }
    }

    private ConcurrentRace() {
    }

    /**
     * Runs {@code racer} on {@code racers} threads at once.
     *
     * @param timeout bound for reaching the start line and, separately, for finishing after the start signal
     * @throws AssertionError if not every racer reaches the start line, or a racer does not finish, in time
     */
    public static <T> Result<T> run(int racers, Duration timeout, Racer<T> racer) {
        if (racers < 2) {
            throw new IllegalArgumentException("a race needs at least 2 racers: " + racers);
        }
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(racer, "racer");

        Gate gate = new Gate(racers, timeout);
        AtomicInteger threads = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(racers, task -> {
            Thread thread = new Thread(task, "race-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<T>> futures = new ArrayList<>(racers);
            for (int i = 0; i < racers; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    AtomicBoolean arrived = new AtomicBoolean();
                    try {
                        return racer.run(index, () -> gate.arrive(arrived));
                    } finally {
                        gate.leftWithoutArriving(arrived);
                    }
                }));
            }

            gate.startWhenAllReady();

            long deadline = System.nanoTime() + timeout.toNanos();
            List<Outcome<T>> outcomes = new ArrayList<>(racers);
            for (int i = 0; i < racers; i++) {
                outcomes.add(outcome(i, futures.get(i), deadline));
            }
            return new Result<>(racers, threads.get(), outcomes);
        } finally {
            executor.shutdownNow();
        }
    }

    private static <T> Outcome<T> outcome(int racer, Future<T> future, long deadline) {
        try {
            return new Outcome.Won<>(racer, future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
        } catch (ExecutionException e) {
            return new Outcome.Lost<>(racer, e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("racer " + racer + " did not finish in time after the start signal", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for racer " + racer, e);
        }
    }

    /** Ready latch (N) + one start signal. */
    private static final class Gate {

        private final int racers;
        private final Duration timeout;
        private final CountDownLatch ready;
        private final CountDownLatch start = new CountDownLatch(1);
        private final AtomicInteger skipped = new AtomicInteger();

        Gate(int racers, Duration timeout) {
            this.racers = racers;
            this.timeout = timeout;
            this.ready = new CountDownLatch(racers);
        }

        void arrive(AtomicBoolean arrived) {
            if (arrived.compareAndSet(false, true)) {
                ready.countDown();
            }
            try {
                if (!start.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                    throw new IllegalStateException("the start signal never came");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted at the start line", e);
            }
        }

        /** A racer that ended (returned or threw) before reaching the start line must not stall the others. */
        void leftWithoutArriving(AtomicBoolean arrived) {
            if (arrived.compareAndSet(false, true)) {
                skipped.incrementAndGet();
                ready.countDown();
            }
        }

        void startWhenAllReady() {
            try {
                if (!ready.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                    throw new AssertionError("only %d of %d racers reached the start line in %s"
                            .formatted(racers - ready.getCount(), racers, timeout));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted before the start signal", e);
            }
            if (skipped.get() > 0) {
                start.countDown();
                throw new AssertionError("%d of %d racers ended without reaching the start line"
                        .formatted(skipped.get(), racers));
            }
            start.countDown();
        }
    }
}
