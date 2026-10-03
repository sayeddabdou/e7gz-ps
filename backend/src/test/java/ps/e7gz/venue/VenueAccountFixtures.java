package ps.e7gz.venue;

import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.util.UUID;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-24 test data, written as the migration owner: an area, an ONBOARDING venue with one activity, and its ENABLED
 * venue account with a real Argon2id Owner Password in {@code iam.venue_owner_secret}.
 */
final class VenueAccountFixtures {

    static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(
            new Driver(), PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    private VenueAccountFixtures() {
    }

    record VenueWithAccount(UUID areaId, UUID venueId, UUID accountId) {
    }

    static VenueWithAccount venueWithAccount(PasswordEncoder passwords, String ownerPassword) {
        UUID area = UUID.randomUUID();
        OWNER.sql("INSERT INTO geo.area (id, name, is_active) VALUES (:id, :name, true)")
                .param("id", area).param("name", "Area " + area).update();
        UUID venue = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
                        VALUES (:id, :area, :name, '12 Test St', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING',
                                'Africa/Cairo', '+20223456789')""")
                .param("id", venue).param("area", area).param("name", "Venue " + venue).update();
        OWNER.sql("INSERT INTO venue.venue_activity (id, venue_id, activity) VALUES (:id, :venue, 'PLAYSTATION')")
                .param("id", UUID.randomUUID()).param("venue", venue).update();
        UUID account = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO iam.venue_account (id, venue_id, login_identifier, password_hash, account_status,
                                                       language_preference)
                        VALUES (:id, :venue, :login, 'not-used-here', 'ENABLED', 'en')""")
                .param("id", account).param("venue", venue).param("login", "venue-" + account).update();
        OWNER.sql("""
                        INSERT INTO iam.venue_owner_secret (venue_account_id, owner_password_hash, changed_at, version)
                        VALUES (:account, :hash, now() - interval '1 day', 0)""")
                .param("account", account).param("hash", passwords.encode(ownerPassword)).update();
        return new VenueWithAccount(area, venue, account);
    }

    /**
     * A confirmed photo written directly (no file): enough for order, removal and ownership. Marked processed so the
     * copy job, which other test classes run against the same database, never picks it up.
     */
    static UUID visiblePhoto(UUID venueId, int position) {
        UUID id = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO venue.venue_image (id, venue_id, object_key, content_type, sort_order, uploaded_at,
                                                       processed_at)
                        VALUES (:id, :venue, :key, 'image/jpeg', :position, now(), now())""")
                .param("id", id).param("venue", venueId).param("key", "venues/" + venueId + "/" + id + ".jpg")
                .param("position", position).update();
        return id;
    }

    static long auditCount(String action, UUID subject) {
        return OWNER.sql("SELECT count(*) FROM audit.audit_event WHERE action = :action AND subject_id = :subject")
                .param("action", action).param("subject", subject).query(Long.class).single();
    }
}
