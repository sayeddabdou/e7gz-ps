package ps.e7gz.support.boundary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A UTC {@link Clock} the test sets and moves (arch 08 section 8). It replaces the JVM clock only - token expiry,
 * {@code meta.serverTime}, the worker's own bookkeeping. Command rules and the deadline worker read the database
 * clock; {@link Deadlines} and {@link DueJobs} handle those. Make it the context's {@code Clock} with
 * {@link TestClockConfig}.
 */
public final class TestClock extends Clock {

    private volatile Instant now;

    public TestClock(Instant start) {
        this.now = start;
    }

    public void set(Instant instant) {
        this.now = instant;
    }

    public void advance(Duration by) {
        this.now = now.plus(by);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        if (!ZoneOffset.UTC.equals(zone)) {
            throw new UnsupportedOperationException("The test clock is UTC, like the system clock it replaces");
        }
        return this;
    }
}
