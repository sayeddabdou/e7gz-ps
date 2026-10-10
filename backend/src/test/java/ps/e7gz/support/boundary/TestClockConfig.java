package ps.e7gz.support.boundary;

import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * {@code @Import(TestClockConfig.class)} makes a {@link TestClock}, starting at the real time, the context's one
 * {@code Clock} ({@code ClockConfig} steps aside for it); autowire {@code TestClock} to move it. A context with it is a
 * context of its own in Spring's test cache, so import it only where the JVM clock matters.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

    @Bean
    TestClock testClock() {
        return new TestClock(Clock.systemUTC().instant());
    }
}
