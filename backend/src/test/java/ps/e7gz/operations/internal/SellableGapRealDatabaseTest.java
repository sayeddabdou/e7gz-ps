package ps.e7gz.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.common.codes.EmergencyScope;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.inventory.sweep.api.InvariantCheck;
import ps.e7gz.inventory.sweep.api.InvariantViolation;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-79, FR-OPS-21: the sellable-gap check against the real migrated database. Emergencies declared through the real
 * commands (E7-82) hold their selling blocks, so the check finds no gap for them; and when a block is ended behind the
 * guards - inside a transaction that is rolled back, so nothing is left in the shared database - the same production
 * SQL finds exactly that Emergency, for a room and for a venue.
 */
class SellableGapRealDatabaseTest extends IntegrationTest {

    static final String INVARIANT = "FR_OPS_21_SELLABLE_GAP";

    @Autowired
    ApplicationContext context;

    @Autowired
    CommandRunner runner;

    @Autowired
    VenueEmergencies emergencies;

    @Autowired
    List<InvariantCheck> checks;

    private BookingFlows flows;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        flows = BookingFlows.of(context);
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    @Test
    void realEmergenciesHoldTheirBlocksSoTheCheckFindsNoGap() {
        UUID room = declare(flows.pending(), EmergencyScope.RESOURCE);
        UUID venue = declare(flows.pending(), EmergencyScope.VENUE);

        inRolledBackTransaction(db -> assertThat(gapsAmong(db, room, venue)).isEmpty());
    }

    @Test
    void aRoomBlockEndedBehindTheGuardsIsFound() {
        UUID room = declare(flows.pending(), EmergencyScope.RESOURCE);

        inRolledBackTransaction(db -> {
            db.sql("""
                            UPDATE resource.resource_selling_block SET active = false, ended_at = started_at
                             WHERE emergency_id = :id""").param("id", room).update();
            assertThat(gapsAmong(db, room)).singleElement().satisfies(v ->
                    assertThat(v.facts()).containsEntry("scope", "RESOURCE").containsEntry("status", "ACTIVE"));
        });
        inRolledBackTransaction(db -> assertThat(gapsAmong(db, room)).as("rolled back").isEmpty());
    }

    @Test
    void aVenueBlockEndedBehindTheGuardsIsFound() {
        UUID venue = declare(flows.pending(), EmergencyScope.VENUE);

        inRolledBackTransaction(db -> {
            db.sql("""
                            UPDATE venue.venue_selling_block SET active = false, ended_at = started_at
                             WHERE emergency_id = :id""").param("id", venue).update();
            assertThat(gapsAmong(db, venue)).singleElement().satisfies(v ->
                    assertThat(v.facts()).containsEntry("scope", "VENUE").doesNotContainKey("resourceId"));
        });
    }

    /** A real Emergency of {@code scope} on the booking's venue or room, starting now, through E7-82's commands. */
    private UUID declare(BookingFlows.Booking at, EmergencyScope scope) {
        EmergencyCommands.Subject subject = new EmergencyCommands.Subject(at.venueId(), scope,
                scope == EmergencyScope.RESOURCE ? at.resourceId() : null);
        Instant end = dbNow().plus(Duration.ofMinutes(20));
        LockSet locks = runner.run(new EmergencyCommands.Plan(at.venueId(), subject, null, end, emergencies.deps()));
        EmergencyCommands.Declaration declaration = new EmergencyCommands.Declaration(subject, at.venueAccountId(),
                CorrelationId.require(), "Power cut", end, locks, emergencies.deps());
        return (scope == EmergencyScope.RESOURCE
                ? runner.run(new EmergencyCommands.DeclareResource(declaration))
                : runner.run(new EmergencyCommands.DeclareVenue(declaration))).id();
    }

    private List<InvariantViolation> gapsAmong(JdbcClient db, UUID... emergencyIds) {
        InvariantCheck check = checks.stream().filter(c -> c.invariant().equals(INVARIANT)).findFirst().orElseThrow();
        List<UUID> mine = List.of(emergencyIds);
        return check.detect(db).stream().filter(v -> mine.contains(v.subjectId())).toList();
    }

    /** One migration-owner transaction with the guards off for it alone, always rolled back. */
    private static void inRolledBackTransaction(Consumer<JdbcClient> body) {
        SingleConnectionDataSource owner = new SingleConnectionDataSource(PostgresTestContainer.jdbcUrl(DATABASE),
                MIGRATOR, MIGRATOR, true);
        owner.setAutoCommit(false);
        try {
            JdbcClient db = JdbcClient.create(owner);
            db.sql("SET LOCAL session_replication_role = replica").update();
            try {
                body.accept(db);
            } finally {
                db.sql("ROLLBACK").update();
            }
        } finally {
            owner.destroy();
        }
    }

    private static Instant dbNow() {
        SingleConnectionDataSource owner = new SingleConnectionDataSource(PostgresTestContainer.jdbcUrl(DATABASE),
                MIGRATOR, MIGRATOR, true);
        try {
            return JdbcClient.create(owner).sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single()
                    .toInstant();
        } finally {
            owner.destroy();
        }
    }
}
