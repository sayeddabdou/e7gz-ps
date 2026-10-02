package ps.e7gz.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.PostgresTestContainer.ADMIN_RUNTIME;
import static ps.e7gz.support.PostgresTestContainer.APP_RUNTIME;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.identity.internal.TestAccounts;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-23, Sprint 0 review S-6 (Product Owner decision 2 Oct 2026): the public API's role ({@code e7gz_app_runtime})
 * cannot write the Platform Administration tables or read admin credentials; the admin role still can. Checked against
 * the database migrated by our migrations and security baseline.
 */
class AdminAccountPrivilegesTest extends IntegrationTest {

    private final JdbcClient app = connect(APP_RUNTIME);
    private final JdbcClient admin = connect(ADMIN_RUNTIME);

    private UUID adminId;

    @BeforeEach
    void anAdminExists() {
        adminId = TestAccounts.admin("privileges-" + UUID.randomUUID() + "@example.com", "x");
    }

    @Test
    void theAppRoleCannotInsertAnAdmin() {
        UUID id = UUID.randomUUID();
        assertPermissionDenied(() -> app.sql("""
                        INSERT INTO iam.admin_account (id, email, password_hash, full_name)
                        VALUES (:id, :email, 'x', 'Intruder')""")
                .param("id", id).param("email", id + "@example.com").update());
    }

    @Test
    void theAppRoleCannotUpdateAnAdmin() {
        assertPermissionDenied(() -> app.sql("UPDATE iam.admin_account SET password_hash = 'y' WHERE id = :id")
                .param("id", adminId).update());
        assertPermissionDenied(() -> app.sql("UPDATE iam.admin_account SET is_active = false WHERE id = :id")
                .param("id", adminId).update());
    }

    @Test
    void theAppRoleCannotReadAdminCredentials() {
        assertPermissionDenied(() -> app.sql("SELECT password_hash FROM iam.admin_account WHERE id = :id")
                .param("id", adminId).query(String.class).list());
        assertPermissionDenied(() -> app.sql("SELECT email FROM iam.admin_account WHERE id = :id")
                .param("id", adminId).query(String.class).list());
    }

    /** What its SECURITY INVOKER triggers read to check an ADMIN actor. */
    @Test
    void theAppRoleCanStillCheckThatAnAdminIsActive() {
        assertThat(app.sql("SELECT is_active FROM iam.admin_account WHERE id = :id")
                .param("id", adminId).query(Boolean.class).single()).isTrue();
    }

    @Test
    void theAppRoleCannotWriteAdminAlerts() {
        assertPermissionDenied(() -> app.sql("""
                        INSERT INTO admin.admin_alert (id, kind, severity, subject_type, subject_id, correlation_id)
                        VALUES (gen_random_uuid(), 'X', 'RISK', 'VENUE', gen_random_uuid(), gen_random_uuid())""")
                .update());
        assertPermissionDenied(() -> app.sql("UPDATE admin.admin_alert SET acknowledged_at = now() WHERE false")
                .update());
    }

    /** V202610021800: the O11 abuse-signal trigger writes alerts as its owner, not as the app role. */
    @Test
    void theAbuseSignalTriggerRunsAsItsOwner() {
        assertThat(app.sql("""
                        SELECT p.prosecdef FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                         WHERE n.nspname = 'operations'
                           AND p.proname = 'raise_resource_emergency_tournament_abuse_alert'""")
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void theAdminRoleCanStillWriteAndReadAdmins() {
        admin.sql("UPDATE iam.admin_account SET full_name = 'Renamed' WHERE id = :id").param("id", adminId).update();
        assertThat(admin.sql("SELECT full_name FROM iam.admin_account WHERE id = :id")
                .param("id", adminId).query(String.class).single()).isEqualTo("Renamed");
    }

    private static void assertPermissionDenied(Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
    }

    private static JdbcClient connect(String role) {
        return JdbcClient.create(new SimpleDriverDataSource(
                new Driver(), PostgresTestContainer.jdbcUrl(DATABASE), role, role));
    }
}
