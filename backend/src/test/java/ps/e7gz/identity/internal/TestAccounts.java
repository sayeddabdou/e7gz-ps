package ps.e7gz.identity.internal;

import java.util.UUID;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-14 test data: real {@code iam.venue_account} (with its area and ONBOARDING venue) and {@code iam.admin_account}
 * rows in the migrated database, read by the production Venue and Admin login adapters.
 */
public final class TestAccounts {

    private TestAccounts() {
    }

    /** An ENABLED venue account. */
    public static UUID venue(JdbcClient db, String loginIdentifier, String passwordHash) {
        UUID area = UUID.randomUUID();
        UUID venue = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        db.sql("INSERT INTO geo.area (id, name) VALUES (:id, :name)").param("id", area).param("name", "area-" + area)
                .update();
        db.sql("""
                        INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
                        VALUES (:id, :area, :name, 'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING',
                                'Africa/Cairo', '+200000000')""")
                .param("id", venue).param("area", area).param("name", "venue-" + venue).update();
        db.sql("""
                        INSERT INTO iam.venue_account (id, venue_id, login_identifier, password_hash, account_status,
                                                       language_preference)
                        VALUES (:id, :venue, :login, :hash, :status, 'en')""")
                .param("id", account).param("venue", venue).param("login", loginIdentifier).param("hash", passwordHash)
                .param("status", VenueAccountStatus.ENABLED.name()).update();
        return account;
    }

    public static void setVenueStatus(JdbcClient db, UUID account, VenueAccountStatus status) {
        db.sql("UPDATE iam.venue_account SET account_status = :status WHERE id = :id")
                .param("status", status.name()).param("id", account).update();
    }

    /**
     * An active admin, written on the admin-runtime connection: since E7-23 (Sprint 0 review S-6) the public API's role
     * cannot write {@code iam.admin_account}.
     */
    public static UUID admin(String email, String passwordHash) {
        UUID id = UUID.randomUUID();
        adminRuntime().sql("""
                        INSERT INTO iam.admin_account (id, email, password_hash, full_name)
                        VALUES (:id, :email, :hash, 'Test')""")
                .param("id", id).param("email", email).param("hash", passwordHash).update();
        return id;
    }

    public static void setAdminActive(UUID admin, boolean active) {
        adminRuntime().sql("UPDATE iam.admin_account SET is_active = :active WHERE id = :id")
                .param("active", active).param("id", admin).update();
    }

    private static JdbcClient adminRuntime() {
        return JdbcClient.create(new SimpleDriverDataSource(new Driver(),
                PostgresTestContainer.jdbcUrl(PostgresTestContainer.DATABASE), PostgresTestContainer.ADMIN_RUNTIME,
                PostgresTestContainer.ADMIN_RUNTIME));
    }
}
