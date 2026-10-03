package ps.e7gz.identity.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing (arch 10 section 2.2): Argon2id, m = 64 MiB, t = 3, p = 1, 16-byte salt. The encoded value carries
 * its own parameters and salt, so the parameters can be raised later without breaking stored hashes. Every hash and
 * check goes through one bounded gate ({@link GatedPasswordEncoder}, Sprint 0 review S-2).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PasswordHashingProperties.class)
class PasswordHashingConfig {

    static final int SALT_LENGTH = 16;
    static final int HASH_LENGTH = 32;
    static final int PARALLELISM = 1;
    static final int MEMORY_KIB = 64 * 1024;
    static final int ITERATIONS = 3;

    @Bean
    PasswordEncoder passwordEncoder(PasswordHashingProperties gate) {
        return new GatedPasswordEncoder(
                new Argon2PasswordEncoder(SALT_LENGTH, HASH_LENGTH, PARALLELISM, MEMORY_KIB, ITERATIONS),
                gate.maxConcurrent(), gate.acquireTimeout());
    }
}
