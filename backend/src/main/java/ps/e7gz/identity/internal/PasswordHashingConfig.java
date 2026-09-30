package ps.e7gz.identity.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Customer password hashing (arch 10 section 2.2): Argon2id, m = 64 MiB, t = 3, p = 1, 16-byte salt. The encoded
 * value carries its own parameters and salt, so the parameters can be raised later without breaking stored hashes.
 */
@Configuration(proxyBeanMethods = false)
class PasswordHashingConfig {

    static final int SALT_LENGTH = 16;
    static final int HASH_LENGTH = 32;
    static final int PARALLELISM = 1;
    static final int MEMORY_KIB = 64 * 1024;
    static final int ITERATIONS = 3;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(SALT_LENGTH, HASH_LENGTH, PARALLELISM, MEMORY_KIB, ITERATIONS);
    }
}
