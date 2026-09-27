package ps.e7gz.common.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CommandRetryPropertiesTest {

    private final CommandRetryProperties retry =
            new CommandRetryProperties(5, Duration.ofMillis(10), Duration.ofMillis(200));

    @Test
    void backoffIsJitteredWithinAnExponentialCap() {
        Random random = new Random(42);
        for (int attempt = 1; attempt <= 10; attempt++) {
            long cap = Math.min(200, 10L << (attempt - 1));
            for (int i = 0; i < 100; i++) {
                assertThat(retry.backoff(attempt, random).toMillis()).isBetween(0L, cap);
            }
        }
    }

    @Test
    void backoffIsNotConstant() {
        Random random = new Random(7);
        assertThat(java.util.stream.IntStream.range(0, 50).mapToObj(i -> retry.backoff(4, random)).distinct().count())
                .isGreaterThan(1);
    }

    @Test
    void atLeastOneAttemptIsRequired() {
        assertThatThrownBy(() -> new CommandRetryProperties(0, Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
