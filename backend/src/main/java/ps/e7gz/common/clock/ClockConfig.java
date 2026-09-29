package ps.e7gz.common.clock;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one injected {@link Clock} (arch 08 §8). This package is the only place the system time source is touched; an
 * ArchUnit rule bans {@code Instant.now()}, {@code LocalDateTime.now()}, {@code System.currentTimeMillis()} and their
 * kin everywhere else, so every boundary test can move time by supplying its own {@code Clock} bean.
 *
 * <p>Inside a command, the rule time is {@code CommandContext.commandNow()} — the instant the database sampled when
 * the command context opened — not this clock (arch 04 §2.1). Workers compare deadlines against the database clock.
 * This clock is for everything else the JVM stamps, such as {@code meta.serverTime} and token expiry.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /** UTC system clock; a test replaces it by declaring its own {@code Clock} bean. */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
