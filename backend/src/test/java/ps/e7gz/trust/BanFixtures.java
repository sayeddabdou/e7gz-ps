package ps.e7gz.trust;

import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import ps.e7gz.support.PostgresTestContainer;

/**
 * {@code trust.ban_instance} rows for tests, written as the migration owner. A Ban normally comes from a qualification
 * and its cascade (FR-TRN-*, Trust engine); these rows stand alone, so the qualification and cascade triggers are
 * switched off for the one insert ({@code session_replication_role = replica}, as {@code GuardErrorIdentifiersTest} does).
 */
public final class BanFixtures {

    private BanFixtures() {
    }

    /** A Ban of {@code userId} with {@code status}, from {@code activatedAt} for {@code duration}. */
    public static UUID ban(UUID userId, String status, Instant activatedAt, Duration duration) {
        UUID id = UUID.randomUUID();
        try (Connection owner = DriverManager.getConnection(PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR,
                MIGRATOR)) {
            owner.setAutoCommit(false);
            try (Statement replica = owner.createStatement()) {
                replica.execute("SET LOCAL session_replication_role = replica");
            }
            try (PreparedStatement insert = owner.prepareStatement("""
                    INSERT INTO trust.ban_instance (id, user_id, qualification_id, penalty_stage, activated_at, ends_at,
                                                    duration, status, activation_reason, latest_qualification_id,
                                                    last_command_at)
                    VALUES (?, ?, gen_random_uuid(), 1, ?, ?, make_interval(secs => ?), ?, 'test ban',
                            gen_random_uuid(), ?)""")) {
                OffsetDateTime from = OffsetDateTime.ofInstant(activatedAt, ZoneOffset.UTC);
                insert.setObject(1, id);
                insert.setObject(2, userId);
                insert.setObject(3, from);
                insert.setObject(4, from.plus(duration));
                insert.setLong(5, duration.toSeconds());
                insert.setString(6, status);
                insert.setObject(7, from);
                insert.executeUpdate();
            }
            owner.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Ban fixture failed", e);
        }
        return id;
    }

    /** An ACTIVE Ban in force now (database time): activated an hour ago, for 7 days. */
    public static UUID activeBan(UUID userId) {
        try (Connection owner = DriverManager.getConnection(PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR);
             Statement now = owner.createStatement();
             ResultSet rs = now.executeQuery("SELECT clock_timestamp()")) {
            rs.next();
            Instant dbNow = rs.getObject(1, OffsetDateTime.class).toInstant();
            return ban(userId, "ACTIVE", dbNow.minus(Duration.ofHours(1)), Duration.ofDays(7));
        } catch (SQLException e) {
            throw new IllegalStateException("Ban fixture failed", e);
        }
    }
}
