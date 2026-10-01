package ps.e7gz.common.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ps.e7gz.common.codes.AuthSubjectType;

/**
 * An authenticated API request: the subject of a verified access JWT whose account is still active. Its one authority
 * names the identity type ({@link #authorityFor}); it is derived on the server from {@code typ} and routes the request
 * to its own route group (arch 09 section 1.1). It is not a Product role and never travels in the token.
 */
public final class SubjectAuthentication extends AbstractAuthenticationToken {

    private final AuthenticatedSubject subject;

    SubjectAuthentication(AuthenticatedSubject subject) {
        super(List.of(new SimpleGrantedAuthority(authorityFor(subject.type()))));
        this.subject = subject;
        setAuthenticated(true);
    }

    static String authorityFor(AuthSubjectType type) {
        return "SUBJECT_TYPE_" + type.name();
    }

    @Override
    public AuthenticatedSubject getPrincipal() {
        return subject;
    }

    /** The token is not kept once verified. */
    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return subject.id().toString();
    }
}
