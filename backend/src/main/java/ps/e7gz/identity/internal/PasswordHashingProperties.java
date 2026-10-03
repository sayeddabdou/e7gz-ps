package ps.e7gz.identity.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The password-hashing gate ({@code e7gz.password-hashing.*}, Sprint 0 review S-2, E7-24). Every Argon2id hash and
 * check takes 64 MiB, so at most {@code maxConcurrent} run at once on one instance; a request that waits longer than
 * {@code acquireTimeout} for a turn is refused with {@code RATE_LIMITED}.
 *
 * @param maxConcurrent  hashes and checks allowed at the same time on one instance
 * @param acquireTimeout how long a request waits for a turn before it is refused
 */
@ConfigurationProperties("e7gz.password-hashing")
record PasswordHashingProperties(
        @DefaultValue("4") int maxConcurrent,
        @DefaultValue("2s") Duration acquireTimeout) {

    PasswordHashingProperties {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("e7gz.password-hashing.max-concurrent must be at least 1");
        }
        if (acquireTimeout == null || acquireTimeout.isNegative()) {
            throw new IllegalArgumentException("e7gz.password-hashing.acquire-timeout must not be negative");
        }
    }
}
