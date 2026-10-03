package ps.e7gz.inventory;

import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import ps.e7gz.support.PostgresTestContainer;

/**
 * The fixed owner rows of {@code e7-32-fixtures.sql}, loaded once per test JVM as the migrator (owner), outside any
 * command - test setup only. Shared by the E7-32 race test (ledger package) and the sweep tests.
 */
public final class E732Fixtures {

    public static final UUID VENUE = id(0x002);

    public static final UUID RACE_RESOURCE = id(0x101);
    /** Carries two overlapping Maintenance windows and a Closure overlapping both, all in 2031-01-01. */
    public static final UUID MAINTENANCE_RESOURCE = id(0x102);
    public static final UUID RELEASED_HISTORY_RESOURCE = id(0x103);
    public static final UUID COMMITMENTS_RESOURCE = id(0x104);

    /** CONFIRMED Manual Booking owning the sweep tests' BOOKING rows. */
    public static final UUID BOOKING = id(0x201);
    /** [2031-01-01 08:00, 10:00) UTC on {@link #MAINTENANCE_RESOURCE}. */
    public static final UUID MAINTENANCE_1 = id(0x301);
    /** [2031-01-01 09:00, 11:00) UTC on {@link #MAINTENANCE_RESOURCE}. */
    public static final UUID MAINTENANCE_2 = id(0x302);
    /** [2031-01-01 09:30, 10:30) UTC on {@link #VENUE}. */
    public static final UUID CLOSURE = id(0x401);

    private static final String SCRIPT = "ps/e7gz/inventory/e7-32-fixtures.sql";

    private static boolean loaded;

    private E732Fixtures() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        try (Connection owner = DriverManager.getConnection(PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR)) {
            ScriptUtils.executeSqlScript(owner, new ClassPathResource(SCRIPT));
        } catch (SQLException e) {
            throw new IllegalStateException("E7-32 fixtures failed", e);
        }
        loaded = true;
    }

    private static UUID id(int n) {
        return UUID.fromString("e7320000-0000-4000-8000-%012x".formatted(n));
    }
}
