package ps.e7gz.common.codes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.PostgresTestContainer.APP_RUNTIME;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ps.e7gz.common.codes.ClosedValueContract.Canonical;
import ps.e7gz.common.codes.ClosedValueContract.ContractViolation;
import ps.e7gz.common.codes.ClosedValueContract.Parity;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-16 - Java enums vs the closed CHECK lists of the migrated database, read from {@code pg_constraint} with
 * {@code pg_get_constraintdef} (never from the migration file).
 */
class ClosedValueParityTest extends IntegrationTest {

    /** Every CHECK constraint on a table of the model, keyed {@code schema.table.constraint}. */
    private static final String CHECK_CONSTRAINTS = """
            SELECT n.nspname || '.' || t.relname || '.' || c.conname AS key, pg_get_constraintdef(c.oid) AS definition
              FROM pg_constraint c
              JOIN pg_class t ON t.oid = c.conrelid
              JOIN pg_namespace n ON n.oid = t.relnamespace
             WHERE c.contype = 'c'
               AND n.nspname NOT IN ('pg_catalog', 'information_schema', 'public', 'flyway')
               AND n.nspname NOT LIKE 'pg\\_%'
             ORDER BY 1
            """;

    @Autowired
    DataSource appDataSource;

    private Map<String, String> definitions;

    @BeforeEach
    void loadConstraints() {
        definitions = new LinkedHashMap<>();
        new JdbcTemplate(appDataSource).query(CHECK_CONSTRAINTS,
                rs -> {
                    definitions.put(rs.getString("key"), rs.getString("definition"));
                });
        assertThat(definitions).as("the migrated model has CHECK constraints").hasSizeGreaterThan(100);
    }

    @Test
    void everyMappedEnumEqualsEveryCanonicalDatabaseListExactly() {
        List<String> failures = new ArrayList<>();
        for (Canonical mapping : ClosedValueContract.CANONICAL) {
            try {
                List<String> db = ClosedValueContract.extract(mapping, definitions);
                Parity parity = ClosedValueContract.compare(mapping.type(), db);
                if (!parity.matches()) {
                    failures.add(mapping + ": in DB but not Java " + parity.missingInJava()
                            + ", in Java but not DB " + parity.extraInJava());
                }
            } catch (ContractViolation | CheckConstraintParser.MalformedConstraintException e) {
                failures.add(e.getMessage());
            }
        }
        assertThat(failures).as("enum <-> CHECK parity").isEmpty();
    }

    @Test
    void everyClosedListInTheDatabaseIsAccountedFor() {
        assertThat(ClosedValueContract.coverageViolations(definitions)).isEmpty();
    }

    @Test
    void theDatabaseCheckOfBookingEventTypeIsTheCanonicalTwelve() {
        Canonical mapping = mapping(BookingEventType.class, "booking.booking_history.ck_booking_history_event_type_values");

        assertThat(ClosedValueContract.extract(mapping, definitions))
                .hasSize(12)
                .containsExactlyElementsOf(ClosedValueContract.names(BookingEventType.class));
    }

    @Test
    void literalBranchDomainsMatchTheLiveDatabase() {
        assertThat(ClosedValueContract.extract(
                mapping(IdempotencyState.class, "technical.idempotency_record.ck_idempotency_state"), definitions))
                .containsExactlyElementsOf(ClosedValueContract.names(IdempotencyState.class));
        assertThat(ClosedValueContract.extract(
                mapping(RewardSourceKind.class, "loyalty.reward_application.ck_reward_application_source"), definitions))
                .containsExactlyElementsOf(ClosedValueContract.names(RewardSourceKind.class));
    }

    @Test
    void changingALiteralBranchDomainInTheDatabaseFails() throws SQLException {
        Canonical real = mapping(IdempotencyState.class, "technical.idempotency_record.ck_idempotency_state");
        try (Connection connection = DriverManager.getConnection(
                PostgresTestContainer.jdbcUrl(DATABASE), APP_RUNTIME, APP_RUNTIME);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TEMP TABLE e7gz_parity_renamed (state text, done_at timestamptz, CONSTRAINT ck_probe CHECK (
                        (state = 'IN_PROGRESS' AND done_at IS NULL) OR (state = 'DONE' AND done_at IS NOT NULL)))
                    """);
            statement.execute("""
                    CREATE TEMP TABLE e7gz_parity_extra_branch (state text, CONSTRAINT ck_probe CHECK (
                        (state = 'IN_PROGRESS') OR (state = 'COMPLETED') OR (state = 'FAILED')))
                    """);

            Parity renamed = ClosedValueContract.compare(IdempotencyState.class,
                    ClosedValueContract.extract(real, definition(statement, "e7gz_parity_renamed")));
            assertThat(renamed.missingInJava()).containsExactly("DONE");
            assertThat(renamed.extraInJava()).containsExactly("COMPLETED");
            assertThatThrownBy(() -> ClosedValueContract.extract(real, definition(statement, "e7gz_parity_extra_branch")))
                    .isInstanceOf(ContractViolation.class)
                    .hasMessageContaining("expected 2");
        }
    }

    // ---- Done-when proofs: a change on either side fails parity ------------------------------------------------

    /** BookingStatus with NO_SHOW removed, and with an invented member added. */
    enum BookingStatusMissingMember { PENDING, VENUE_RESPONSE_EXPIRED, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED, ACTIVE,
        COMPLETED, REJECTED, CANCELLED }

    enum BookingStatusExtraMember { PENDING, VENUE_RESPONSE_EXPIRED, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED, ACTIVE,
        COMPLETED, REJECTED, CANCELLED, NO_SHOW, ON_HOLD }

    enum BookingStatusRenamedMember { PENDING, VENUE_RESPONSE_EXPIRED, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED, ACTIVE,
        COMPLETED, REJECTED, CANCELLED, NOSHOW }

    @Test
    void changingAJavaEnumWhileTheDatabaseIsUnchangedFails() {
        List<String> db = ClosedValueContract.extract(
                mapping(BookingStatus.class, "booking.booking.ck_booking_status_values"), definitions);

        assertThat(ClosedValueContract.compare(BookingStatus.class, db).matches()).isTrue();
        assertThat(ClosedValueContract.compare(BookingStatusMissingMember.class, db).missingInJava()).containsExactly("NO_SHOW");
        assertThat(ClosedValueContract.compare(BookingStatusExtraMember.class, db).extraInJava()).containsExactly("ON_HOLD");
        Parity renamed = ClosedValueContract.compare(BookingStatusRenamedMember.class, db);
        assertThat(renamed.missingInJava()).containsExactly("NO_SHOW");
        assertThat(renamed.extraInJava()).containsExactly("NOSHOW");
    }

    @Test
    void changingTheDatabaseListWhileJavaIsUnchangedFails() throws SQLException {
        Canonical real = mapping(BookingStatus.class, "booking.booking.ck_booking_status_values");
        // Same column and shape as booking.booking.status, in a session-private table: V1 is never touched.
        try (Connection connection = DriverManager.getConnection(
                PostgresTestContainer.jdbcUrl(DATABASE), APP_RUNTIME, APP_RUNTIME);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TEMP TABLE e7gz_parity_dropped (status varchar(80) CONSTRAINT ck_probe CHECK (status IN (
                        'PENDING','VENUE_RESPONSE_EXPIRED','AWAITING_CUSTOMER_CONFIRMATION','CONFIRMED','ACTIVE',
                        'COMPLETED','REJECTED','CANCELLED')))
                    """);
            statement.execute("""
                    CREATE TEMP TABLE e7gz_parity_added (status varchar(80) CONSTRAINT ck_probe CHECK (status IN (
                        'PENDING','VENUE_RESPONSE_EXPIRED','AWAITING_CUSTOMER_CONFIRMATION','CONFIRMED','ACTIVE',
                        'COMPLETED','REJECTED','CANCELLED','NO_SHOW','ON_HOLD')))
                    """);
            String dropped = definition(statement, "e7gz_parity_dropped");
            String added = definition(statement, "e7gz_parity_added");

            assertThat(ClosedValueContract.compare(BookingStatus.class, ClosedValueContract.extract(real, dropped))
                    .extraInJava()).containsExactly("NO_SHOW");
            assertThat(ClosedValueContract.compare(BookingStatus.class, ClosedValueContract.extract(real, added))
                    .missingInJava()).containsExactly("ON_HOLD");
        }
    }

    private static String definition(Statement statement, String tempTable) throws SQLException {
        try (ResultSet rs = statement.executeQuery(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_probe'"
                        + " AND conrelid = 'pg_temp." + tempTable + "'::regclass")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static Canonical mapping(Class<? extends Enum<?>> type, String key) {
        return ClosedValueContract.CANONICAL.stream()
                .filter(c -> c.type() == type && c.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no mapping " + type.getSimpleName() + " <- " + key));
    }
}
