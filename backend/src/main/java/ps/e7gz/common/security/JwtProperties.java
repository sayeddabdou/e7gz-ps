package ps.e7gz.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Access-token signing ({@code e7gz.security.jwt.*}, arch 10 section 2.3 and section 6).
 *
 * @param secret the HS512 key, Base64, at least 64 bytes once decoded. It comes from the environment / secret store
 *               ({@code E7GZ_JWT_SECRET}) only; nothing is committed, and the application does not start without it
 */
@ConfigurationProperties("e7gz.security.jwt")
public record JwtProperties(String secret) {

    /** Stops a key from reaching a log through the properties' toString. */
    @Override
    public String toString() {
        return "JwtProperties[secret=<redacted>]";
    }
}
