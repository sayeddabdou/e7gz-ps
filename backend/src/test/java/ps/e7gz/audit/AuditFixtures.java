package ps.e7gz.audit;

import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import ps.e7gz.support.PostgresTestContainer;

/**
 * Real actor rows for audit tests, so {@code audit.guard_audit_insert} checks a real User / Venue Account / Admin.
 * Written as the migrator (owner), outside any command - test setup only.
 */
public final class AuditFixtures {

    private AuditFixtures() {
    }

    public static UUID admin() {
        UUID id = UUID.randomUUID();
        execute("INSERT INTO iam.admin_account(id, email, password_hash, full_name) VALUES (?, ?, 'x', 'Audit Admin')",
                id, id + "@example.test");
        return id;
    }

    public static UUID user() {
        UUID id = UUID.randomUUID();
        String n = id.toString().replace("-", "");
        execute("""
                INSERT INTO iam.user_account(id, full_name, username, phone_e164, email, password_hash, phone_verified,
                                             email_verified, referral_code, joined_at)
                VALUES (?, 'Audit User', ?, ?, ?, 'x', false, false, ?, now() - interval '1 day')
                """, id, "u" + n.substring(0, 20), "+2010" + Math.abs(id.getLeastSignificantBits() % 100_000_000L),
                n + "@example.test", "R" + n.substring(0, 12));
        return id;
    }

    /** A Venue Account (with its area and ONBOARDING venue). */
    public static UUID venueAccount() {
        UUID area = UUID.randomUUID();
        UUID venue = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        execute("INSERT INTO geo.area(id, name) VALUES (?, ?)", area, "area-" + area);
        execute("""
                INSERT INTO venue.venue(id, area_id, name, address, location, marketplace_status, timezone, phone)
                VALUES (?, ?, ?, 'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING', 'Africa/Cairo',
                        '+200000000')
                """, venue, area, "venue-" + venue);
        execute("""
                INSERT INTO iam.venue_account(id, venue_id, login_identifier, password_hash, account_status,
                                              language_preference)
                VALUES (?, ?, ?, 'x', 'ENABLED', 'en')
                """, account, venue, "login-" + account);
        return account;
    }

    private static void execute(String sql, Object... args) {
        try (Connection owner = DriverManager.getConnection(PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR);
             PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.execute();
        } catch (SQLException e) {
            throw new IllegalStateException("Audit fixture failed: " + sql, e);
        }
    }
}
