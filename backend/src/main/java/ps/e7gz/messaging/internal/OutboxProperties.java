package ps.e7gz.messaging.internal;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The outbox dispatcher ({@code e7gz.outbox.*}, arch 08 section 4). Technical settings, not Product rules.
 *
 * @param tick        how often an idle dispatcher polls: the safety net, since {@code NOTIFY} is not durable (arch 08
 *                    section 4.2: 1 second); a {@code pg_notify('outbox')} wakes it sooner
 * @param threads     dispatcher threads per worker process
 * @param retryDelays the wait after each failed attempt (arch 08 section 4.3: attempt 1 immediate, then 5 s, 30 s,
 *                    2 min, 10 min, 1 h); when the attempt after the last delay fails too, the event is
 *                    {@code DEAD_LETTER} (Sev-2)
 */
@ConfigurationProperties("e7gz.outbox")
record OutboxProperties(
        @DefaultValue("1s") Duration tick,
        @DefaultValue("1") int threads,
        @DefaultValue({"5s", "30s", "2m", "10m", "1h"}) List<Duration> retryDelays) {

    OutboxProperties {
        if (tick.toMillis() < 1 || threads < 1) {
            throw new IllegalArgumentException("e7gz.outbox: tick must be positive and threads at least 1");
        }
        retryDelays = List.copyOf(retryDelays);
        if (retryDelays.stream().anyMatch(delay -> delay.toMillis() < 1)) {
            throw new IllegalArgumentException("e7gz.outbox.retry-delays must all be positive");
        }
    }

    /** Attempts in all, the first included: one more than the delays (arch 08 section 4.3: 6). */
    int maxAttempts() {
        return retryDelays.size() + 1;
    }

    /** The wait after failed attempt number {@code attempt} (1-based), which must be below {@link #maxAttempts()}. */
    Duration delayAfter(int attempt) {
        return retryDelays.get(attempt - 1);
    }
}
