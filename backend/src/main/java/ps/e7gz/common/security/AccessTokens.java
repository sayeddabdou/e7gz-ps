package ps.e7gz.common.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import ps.e7gz.common.codes.AuthSubjectType;

/**
 * Issues the access JWT (arch 10 section 2.3): HS512, 15 minutes, and exactly the claims {@code sub}, {@code typ},
 * {@code jti}, {@code iat}, {@code exp}, {@code ver}. No capability or account-state claim, ever: ban state,
 * verification and venue account status are read from the database per request.
 *
 * <p>{@code ver} is the token format version, {@value #TOKEN_VERSION}; the decoder refuses any other value. Arch 10
 * names the claim without defining it; this is a technical reading, raised with the Product Owner (E7-14).
 */
public class AccessTokens {

    public static final Duration LIFETIME = Duration.ofMinutes(15);

    static final String TYPE_CLAIM = "typ";
    static final String VERSION_CLAIM = "ver";
    static final int TOKEN_VERSION = 1;

    private final JwtEncoder encoder;
    private final Clock clock;

    AccessTokens(JwtEncoder encoder, Clock clock) {
        this.encoder = encoder;
        this.clock = clock;
    }

    public String issue(AuthSubjectType type, UUID subjectId) {
        Instant issuedAt = Instant.now(clock);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(subjectId.toString())
                .claim(TYPE_CLAIM, type.name())
                .id(UUID.randomUUID().toString())                                     // type-4 UUID: SecureRandom
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(LIFETIME))
                .claim(VERSION_CLAIM, TOKEN_VERSION)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS512).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
