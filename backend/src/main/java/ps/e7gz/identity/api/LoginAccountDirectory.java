package ps.e7gz.identity.api;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.codes.AuthSubjectType;

/**
 * Finds the login credentials of one identity type for Identity's login (arch 10 section 2.1–2.2). Identity owns
 * login, tokens and the login rate limit; the owning module owns the account table, so it implements this for its
 * type (one bean per {@link AuthSubjectType}):
 * <ul>
 *   <li>{@code CUSTOMER} - Identity ({@code iam.user_account}), username or phone;</li>
 *   <li>{@code VENUE} - Venue ({@code iam.venue_account}), the shared account's login identifier;</li>
 *   <li>{@code ADMIN} - Platform Administration ({@code iam.admin_account}), the admin email.</li>
 * </ul>
 * Identity (layer 0) never reads a higher module's table: the dependency points downward, from the implementing
 * module to this interface (arch 03 section 2.1). Passwords are always verified by Identity, with its one
 * {@code PasswordEncoder}.
 */
public interface LoginAccountDirectory {

    AuthSubjectType subjectType();

    /**
     * The canonical form that keys the login rate limit (trimmed, case-insensitive; E.164 for a phone), so two
     * spellings of one identity are one limiter key. Only ever hashed, never stored.
     */
    String normalise(String loginIdentifier);

    /**
     * The account for the identifier <b>as the client sent it</b>; the implementation applies its own matching rules
     * (for a customer: phone, then username, each with its own normalisation). An anonymised customer is never found.
     */
    Optional<LoginAccount> find(String loginIdentifier);

    /**
     * @param passwordHash the stored Argon2id hash (arch 10 section 2.2)
     * @param active       false when the account may not log in: venue {@code DISABLED} (FR-VEN-08), admin not
     *                     active
     */
    record LoginAccount(UUID id, String passwordHash, boolean active) {

        @Override
        public String toString() {
            return "LoginAccount[id=" + id + ", active=" + active + "]";                    // never the hash
        }
    }

    /** {@code lower(btrim(x))}, the normalised form of {@code ux_venue_login_normalized} / {@code ux_admin_email_normalized}. */
    static String trimmedCaseInsensitive(String loginIdentifier) {
        return loginIdentifier.strip().toLowerCase(Locale.ROOT);
    }
}
