package ps.e7gz.common.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import ps.e7gz.common.codes.AuthSubjectType;

/**
 * The HS512 key and the Nimbus encoder / decoder behind the access JWT (arch 10 section 2.3). The decoder accepts only
 * HS512 with this key, and only a token that carries every required claim, is inside {@code iat..exp} by the injected
 * clock (no skew), has a known {@code typ} and the current {@code ver}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
class AccessTokenConfig {

    /** HS512 needs a key at least as long as its 512-bit output (RFC 7518 section 3.2). */
    static final int MIN_KEY_BYTES = 64;

    @Bean
    SecretKey accessTokenKey(JwtProperties properties) {
        if (properties.secret() == null || properties.secret().isBlank()) {
            throw new IllegalStateException("e7gz.security.jwt.secret (E7GZ_JWT_SECRET) is not set");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(properties.secret().strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("e7gz.security.jwt.secret must be Base64");      // never echo the value
        }
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("e7gz.security.jwt.secret must decode to at least " + MIN_KEY_BYTES
                    + " bytes for HS512");
        }
        return new SecretKeySpec(key, "HmacSHA512");
    }

    @Bean
    AccessTokens accessTokens(SecretKey accessTokenKey, Clock clock) {
        return new AccessTokens(new NimbusJwtEncoder(new ImmutableSecret<>(accessTokenKey)), clock);
    }

    @Bean
    JwtDecoder accessTokenDecoder(SecretKey accessTokenKey, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(accessTokenKey).macAlgorithm(MacAlgorithm.HS512).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, AccessTokenConfig::requiredClaims));
        return decoder;
    }

    /** Exactly the identity claims of arch 10 section 2.3, each well-formed; nothing is defaulted. */
    static OAuth2TokenValidatorResult requiredClaims(Jwt jwt) {
        boolean valid = jwt.getIssuedAt() != null
                && jwt.getExpiresAt() != null
                && jwt.getId() != null && !jwt.getId().isBlank()
                && isUuid(jwt.getSubject())
                && isSubjectType(jwt.getClaimAsString(AccessTokens.TYPE_CLAIM))
                && Integer.valueOf(AccessTokens.TOKEN_VERSION).equals(versionOf(jwt));
        return valid ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN));
    }

    private static Integer versionOf(Jwt jwt) {
        Object ver = jwt.getClaim(AccessTokens.VERSION_CLAIM);
        return (ver instanceof Integer || ver instanceof Long) && ((Number) ver).longValue() == ((Number) ver).intValue()
                ? ((Number) ver).intValue() : null;
    }

    private static boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isSubjectType(String value) {
        for (AuthSubjectType type : AuthSubjectType.values()) {
            if (type.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
