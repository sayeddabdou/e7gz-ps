package ps.e7gz.common.command;

import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import ps.e7gz.support.PostgresTestContainer;

/**
 * Rows the command-runner tests need, written as the migrator (owner) outside any command - test setup only.
 * Venues are ONBOARDING in a fresh active Area, so nothing else in the model depends on them.
 */
final class CommandFixtures {

    private CommandFixtures() {
    }

    /** A connection with owner rights; autocommit is on. */
    static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR);
    }

    static UUID area(Connection owner) throws SQLException {
        UUID id = UUID.randomUUID();
        update(owner, "INSERT INTO geo.area(id, name) VALUES (?, ?)", id, "area-" + id);
        return id;
    }

    static UUID venue(Connection owner, UUID areaId) throws SQLException {
        UUID id = UUID.randomUUID();
        update(owner, """
                INSERT INTO venue.venue(id, area_id, name, address, location, marketplace_status, timezone, phone)
                VALUES (?, ?, ?, 'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING', 'Africa/Cairo', '+200000000')
                """, id, areaId, "venue-" + id);
        update(owner, "INSERT INTO venue.venue_activity(id, venue_id, activity) VALUES (?, ?, 'PLAYSTATION')",
                UUID.randomUUID(), id);
        return id;
    }

    static UUID resource(Connection owner, UUID venueId) throws SQLException {
        UUID id = UUID.randomUUID();
        update(owner, """
                INSERT INTO resource.resource(id, venue_id, activity, name, console_type, base_state, operational_restriction)
                VALUES (?, ?, 'PLAYSTATION', ?, 'PS5', 'ACTIVE', 'NONE')
                """, id, venueId, "room-" + id);
        return id;
    }

    static UUID admin(Connection owner) throws SQLException {
        UUID id = UUID.randomUUID();
        update(owner, "INSERT INTO iam.admin_account(id, email, password_hash, full_name) VALUES (?, ?, 'x', 'Test Admin')",
                id, id + "@example.test");
        return id;
    }

    static void update(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.execute();
        }
    }
}
