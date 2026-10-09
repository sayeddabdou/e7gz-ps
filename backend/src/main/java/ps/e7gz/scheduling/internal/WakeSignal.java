package ps.e7gz.scheduling.internal;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Wakes idle worker threads before their next tick. A wake-up that arrives while a thread is busy is not lost: the
 * thread notes the generation before it polls and returns at once if it changed meanwhile.
 */
final class WakeSignal {

    private final Object monitor = new Object();
    private long generation;

    void wakeAll() {
        synchronized (monitor) {
            generation++;
            monitor.notifyAll();
        }
    }

    long generation() {
        synchronized (monitor) {
            return generation;
        }
    }

    /** Waits until the generation is no longer {@code seen}, or {@code timeout} has passed. */
    void awaitChange(long seen, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (monitor) {
            while (generation == seen) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return;
                }
                TimeUnit.NANOSECONDS.timedWait(monitor, left);
            }
        }
    }
}
