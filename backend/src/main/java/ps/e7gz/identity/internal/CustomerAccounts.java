package ps.e7gz.identity.internal;

import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.security.ActiveSubjectCheck;
import ps.e7gz.identity.api.LoginAccountDirectory;

/**
 * The Customer identity for login and per-request access (arch 10 section 2.1, section 2.3).
 *
 * <p>Login takes a username or a phone (arch 10 section 2.1). An identifier that is a valid Egyptian mobile number is
 * matched as a phone in E.164 ({@link PhoneNumbers}) first, then as a username; anything else as a username, trimmed and
 * case-insensitive like {@code ux_user_username_normalized} (FR-ACC-06). An unverified account can log in (FR-ACC-03:
 * verification gates marketplace actions, not access); an anonymised one cannot (FR-ACC-10).
 *
 * <p>Access: a customer token works while the account exists and is neither anonymised nor released (an unverified
 * account whose phone or username a new registration took, E7-57). Verification and bans are checked
 * by the commands that need them ({@code CustomerActivation}, FR-ACC-17), never here.
 */
@Component
class CustomerAccounts implements LoginAccountDirectory, ActiveSubjectCheck {

    private final JdbcClient jdbc;

    CustomerAccounts(DataSource appDataSource) {
        this.jdbc = JdbcClient.create(appDataSource);
    }

    @Override
    public AuthSubjectType subjectType() {
        return AuthSubjectType.CUSTOMER;
    }

    @Override
    public String normalise(String loginIdentifier) {
        return PhoneNumbers.toE164(loginIdentifier)
                .orElseGet(() -> LoginAccountDirectory.trimmedCaseInsensitive(loginIdentifier));
    }

    /**
     * "Username or phone": a valid phone is matched in E.164 first; when no account has that phone, the original
     * input is matched as a username (trimmed, case-insensitive). No rule forbids a username that looks like a phone.
     *
     * <p>One statement for every input, so a known identifier and an unknown one cost the same database work (no
     * account enumeration through the lookup). When one account's phone and another's username both match, the phone
     * wins (open Product Owner question, E7-14).
     */
    @Override
    public Optional<LoginAccount> find(String loginIdentifier) {
        Optional<String> phone = PhoneNumbers.toE164(loginIdentifier);
        return jdbc.sql("""
                        SELECT id, password_hash FROM iam.user_account
                         WHERE anonymised_at IS NULL
                           AND ((:isPhone AND phone_e164 = :phone)
                                OR lower(btrim(username)) = lower(btrim(:username)))
                         ORDER BY CASE WHEN :isPhone AND phone_e164 = :phone THEN 0 ELSE 1 END
                         LIMIT 1
                        """)
                .param("isPhone", phone.isPresent())
                .param("phone", phone.orElse(""))                       // never compared: :isPhone is false
                .param("username", loginIdentifier)
                .query((rs, n) -> new LoginAccount(rs.getObject("id", UUID.class), rs.getString("password_hash"), true))
                .optional();
    }

    @Override
    public boolean isActive(UUID subjectId) {
        // A released unverified account (S-3, E7-57) holds no session either
        return jdbc.sql("""
                        SELECT count(*) FROM iam.user_account
                         WHERE id = :id AND anonymised_at IS NULL AND phone_e164 NOT LIKE 'released:%'""")
                .param("id", subjectId)
                .query(Integer.class).single() == 1;
    }
}
