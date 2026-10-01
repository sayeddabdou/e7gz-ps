package ps.e7gz.common.security;

import java.util.UUID;
import ps.e7gz.common.codes.AuthSubjectType;

/**
 * Whether an identity may still use its tokens, read from the database on every authenticated request and every
 * refresh (arch 10 section 2.3: "Venue account DISABLED invalidates active sessions immediately: the check runs per
 * request"). Implemented by the module that owns the identity's table, one bean per {@link AuthSubjectType}:
 * <ul>
 *   <li>{@code CUSTOMER} - Identity: the account exists and is not anonymised (FR-ACC-10). Verification and bans are
 *       not access-level: the commands that need them check them (FR-ACC-03, FR-ACC-17).</li>
 *   <li>{@code VENUE} - Venue: {@code account_status = ENABLED} (FR-VEN-08).</li>
 *   <li>{@code ADMIN} - Platform Administration: {@code is_active}.</li>
 * </ul>
 * A type without a bean is never accepted (fail closed).
 */
public interface ActiveSubjectCheck {

    AuthSubjectType subjectType();

    /** False for an unknown id as well. */
    boolean isActive(UUID subjectId);
}
