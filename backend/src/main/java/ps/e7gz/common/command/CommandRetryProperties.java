package ps.e7gz.common.command;

import java.time.Duration;
import java.util.random.RandomGenerator;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bounds of the {@code 40001} retry ({@code e7gz.command.retry.*}).
 *
 * @param maxAttempts    total runs of a command, the first one included
 * @param initialBackoff upper bound of the pause after the first failed attempt
 * @param maxBackoff     upper bound of any pause
 */
@ConfigurationProperties("e7gz.command.retry")
public record CommandRetryProperties(
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("10ms") Duration initialBackoff,
        @DefaultValue("200ms") Duration maxBackoff) {

    public CommandRetryProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("e7gz.command.retry.max-attempts must be at least 1");
        }
        if (initialBackoff.isNegative() || maxBackoff.isNegative()) {
            throw new IllegalArgumentException("e7gz.command.retry backoff must not be negative");
        }
    }

    /** "Full jitter": uniform in [0, min(maxBackoff, initialBackoff x 2^(attempt-1))]. */
    Duration backoff(int failedAttempt, RandomGenerator random) {
        long cap = initialBackoff.toMillis() << Math.min(failedAttempt - 1, 20);
        cap = Math.min(Math.max(cap, 0), maxBackoff.toMillis());
        return Duration.ofMillis(cap == 0 ? 0 : random.nextLong(cap + 1));
    }
}
