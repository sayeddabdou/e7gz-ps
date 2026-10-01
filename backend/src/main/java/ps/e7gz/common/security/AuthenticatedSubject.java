package ps.e7gz.common.security;

import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.AuthSubjectType;

/**
 * Who an access token authenticates: the identity type and its id ({@code iam.user_account},
 * {@code iam.venue_account} or {@code iam.admin_account}). Nothing else: account state is read from the database per
 * request, never from the token (arch 10 section 2.3).
 */
public record AuthenticatedSubject(AuthSubjectType type, UUID id) {

    public AuthenticatedSubject {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }
}
