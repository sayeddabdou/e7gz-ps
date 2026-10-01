package ps.e7gz.venue.internal;

import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.security.ActiveSubjectCheck;
import ps.e7gz.identity.api.LoginAccountDirectory;

/**
 * The shared venue account ({@code iam.venue_account}, FR-VEN-01) for Identity's login and the per-request access check
 * (arch 10 section 2.1, section 2.3). Only {@code ENABLED} may log in or keep using its tokens (FR-VEN-08). Reads only:
 * passwords are verified and tokens issued by Identity.
 */
@Component
class VenueLoginAccounts implements LoginAccountDirectory, ActiveSubjectCheck {

    private final JdbcClient jdbc;

    VenueLoginAccounts(DataSource appDataSource) {
        this.jdbc = JdbcClient.create(appDataSource);
    }

    @Override
    public AuthSubjectType subjectType() {
        return AuthSubjectType.VENUE;
    }

    /** {@code ux_venue_login_normalized}: {@code lower(btrim(login_identifier))}. */
    @Override
    public String normalise(String loginIdentifier) {
        return LoginAccountDirectory.trimmedCaseInsensitive(loginIdentifier);
    }

    @Override
    public Optional<LoginAccount> find(String loginIdentifier) {
        return jdbc.sql("""
                        SELECT id, password_hash, account_status = :enabled AS active
                          FROM iam.venue_account
                         WHERE lower(btrim(login_identifier)) = lower(btrim(:login))
                        """)
                .param("enabled", VenueAccountStatus.ENABLED.name())
                .param("login", loginIdentifier)
                .query((rs, n) -> new LoginAccount(
                        rs.getObject("id", UUID.class), rs.getString("password_hash"), rs.getBoolean("active")))
                .optional();
    }

    @Override
    public boolean isActive(UUID subjectId) {
        return jdbc.sql("SELECT count(*) FROM iam.venue_account WHERE id = :id AND account_status = :enabled")
                .param("id", subjectId)
                .param("enabled", VenueAccountStatus.ENABLED.name())
                .query(Integer.class).single() == 1;
    }
}
