package ps.e7gz.admin.internal;

import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.security.ActiveSubjectCheck;
import ps.e7gz.identity.api.LoginAccountDirectory;

/**
 * Platform Admin identity ({@code iam.admin_account}) for Identity's login and the per-request access check (arch 10
 * section 2.1, section 2.3). Only an active admin may log in or keep using its tokens. Reads only: passwords are
 * verified and tokens issued by Identity.
 */
@Component
class AdminLoginAccounts implements LoginAccountDirectory, ActiveSubjectCheck {

    private final JdbcClient jdbc;

    AdminLoginAccounts(DataSource appDataSource) {
        this.jdbc = JdbcClient.create(appDataSource);
    }

    @Override
    public AuthSubjectType subjectType() {
        return AuthSubjectType.ADMIN;
    }

    /** {@code ux_admin_email_normalized}: {@code lower(btrim(email))}. */
    @Override
    public String normalise(String loginIdentifier) {
        return LoginAccountDirectory.trimmedCaseInsensitive(loginIdentifier);
    }

    @Override
    public Optional<LoginAccount> find(String loginIdentifier) {
        return jdbc.sql("""
                        SELECT id, password_hash, is_active
                          FROM iam.admin_account
                         WHERE email IS NOT NULL AND lower(btrim(email)) = lower(btrim(:email))
                        """)
                .param("email", loginIdentifier)
                .query((rs, n) -> new LoginAccount(
                        rs.getObject("id", UUID.class), rs.getString("password_hash"), rs.getBoolean("is_active")))
                .optional();
    }

    @Override
    public boolean isActive(UUID subjectId) {
        return jdbc.sql("SELECT count(*) FROM iam.admin_account WHERE id = :id AND is_active")
                .param("id", subjectId)
                .query(Integer.class).single() == 1;
    }
}
