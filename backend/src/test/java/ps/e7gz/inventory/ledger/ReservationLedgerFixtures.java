package ps.e7gz.inventory.ledger;

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
 * The fixed owner rows of {@code reservation-ledger-fixtures.sql}, loaded once per test JVM as the migrator (owner),
 * outside any command - test setup only, independent of the Platform venue APIs.
 */
final class ReservationLedgerFixtures {

    static final UUID VENUE = id(0x002);

    /** One Resource per test. */
    static final UUID RACE_RESOURCE = id(0x101);
    static final UUID NO_SHOW_RESOURCE = id(0x102);
    static final UUID ONE_WAY_RESOURCE = id(0x103);
    static final UUID OWNER_SHAPE_RESOURCE = id(0x104);
    static final UUID TRANSLATION_RESOURCE = id(0x105);
    static final UUID TOURNAMENT_RESOURCE = id(0x106);

    static final UUID BOOKING = id(0x201);
    /** Period [2030-01-03 08:00, 09:00) UTC on {@link #OWNER_SHAPE_RESOURCE}. */
    static final UUID MAINTENANCE = id(0x301);
    /** Period [2030-01-04 08:00, 09:00) UTC on {@link #VENUE}. */
    static final UUID CLOSURE = id(0x401);
    static final UUID OPERATING_SESSION = id(0x006);

    private static final String SCRIPT = "ps/e7gz/inventory/ledger/reservation-ledger-fixtures.sql";

    private static boolean loaded;

    private ReservationLedgerFixtures() {
    }

    static synchronized void load() {
        if (loaded) {
            return;
        }
        try (Connection owner = DriverManager.getConnection(PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR)) {
            ScriptUtils.executeSqlScript(owner, new ClassPathResource(SCRIPT));
        } catch (SQLException e) {
            throw new IllegalStateException("Reservation ledger fixtures failed", e);
        }
        loaded = true;
    }

    private static UUID id(int n) {
        return UUID.fromString("e7290000-0000-4000-8000-%012x".formatted(n));
    }
}
