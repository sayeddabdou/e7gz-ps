package ps.e7gz.common.security;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import ps.e7gz.common.codes.AuthSubjectType;

/**
 * A verified access JWT becomes an authenticated request only when its subject is still active in the database now
 * (arch 10 section 2.3): a DISABLED venue account, an inactive admin or an anonymised customer is refused although the
 * token has not expired. A type with no {@link ActiveSubjectCheck} is always refused. A database failure propagates
 * (it is not an authentication failure), so the request never passes.
 */
final class SubjectJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final Map<AuthSubjectType, ActiveSubjectCheck> checks;

    SubjectJwtAuthenticationConverter(Stream<ActiveSubjectCheck> checks) {
        this.checks = byType(checks);
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        // The decoder has validated both claims (AccessTokenConfig.requiredClaims).
        AuthenticatedSubject subject = new AuthenticatedSubject(
                AuthSubjectType.valueOf(jwt.getClaimAsString(AccessTokens.TYPE_CLAIM)), UUID.fromString(jwt.getSubject()));
        ActiveSubjectCheck check = checks.get(subject.type());
        if (check == null || !check.isActive(subject.id())) {
            throw new InvalidBearerTokenException("Token subject is not active");
        }
        return new SubjectAuthentication(subject);
    }

    static Map<AuthSubjectType, ActiveSubjectCheck> byType(Stream<ActiveSubjectCheck> checks) {
        Map<AuthSubjectType, ActiveSubjectCheck> map = new EnumMap<>(AuthSubjectType.class);
        checks.forEach(check -> {
            if (map.putIfAbsent(check.subjectType(), check) != null) {
                throw new IllegalStateException("Two ActiveSubjectCheck beans for " + check.subjectType());
            }
        });
        return map;
    }
}
