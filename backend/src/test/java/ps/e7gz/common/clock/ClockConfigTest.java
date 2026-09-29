package ps.e7gz.common.clock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one injected {@link Clock} (arch 08 §8): UTC by default, replaceable by a test's own clock. */
class ClockConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(ClockConfig.class);

    @Test
    void theDefaultClockIsTheUtcSystemClock() {
        runner.run(context -> {
            Clock clock = context.getBean(Clock.class);
            assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        });
    }

    @Test
    void aTestClockReplacesItSoTimeIsDeterministic() {
        Instant fixed = Instant.parse("2026-10-12T17:04:11Z");
        new ApplicationContextRunner().withUserConfiguration(FixedClock.class, ClockConfig.class).run(context -> {
            assertThat(context.getBeansOfType(Clock.class)).hasSize(1);
            assertThat(Instant.now(context.getBean(Clock.class))).isEqualTo(fixed);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-10-12T17:04:11Z"), ZoneOffset.UTC);
        }
    }
}
