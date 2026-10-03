package ps.e7gz.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.operations.api.OperationalOverlaps;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-30 - {@link OperationalOverlaps} (checks 5-7 of the availability predicate) against the migrated database:
 * half-open overlap, the right Resource / Venue, and only the declared interval of an unresolved Emergency.
 */
class OperationalOverlapsIntegrationTest extends IntegrationTest {

    private static final UUID MAINTENANCE_VENUE = id(0x002);
    private static final UUID OTHER_VENUE = id(0x003);
    private static final UUID VENUE_EMERGENCY_VENUE = id(0x004);
    private static final UUID RESOURCE_EMERGENCY_VENUE = id(0x005);
    private static final UUID ENDED_EMERGENCY_VENUE = id(0x006);

    private static final UUID MAINTAINED = id(0x101);
    private static final UUID UNMAINTAINED = id(0x102);
    private static final UUID VENUE_EMERGENCY_ROOM = id(0x104);
    private static final UUID FAILED_ROOM = id(0x105);
    private static final UUID WORKING_ROOM = id(0x106);
    private static final UUID ENDED_EMERGENCY_ROOM = id(0x107);

    private static final UUID VENUE_EMERGENCY = id(0x704);
    private static final UUID RESOURCE_EMERGENCY = id(0x705);
    private static final UUID ENDED_EMERGENCY = id(0x706);

    private static final String SCRIPT = "ps/e7gz/operations/operational-overlaps-fixtures.sql";    private static boolean loaded;

    @Autowired
    CommandRunner runner;

    @Autowired
    OperationalOverlaps operations;

    @Autowired
    DataSource appDataSource;

    record TestCommand<R>(LockSet locks, Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(locks);
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }

    static final class RollBack extends RuntimeException {

        RollBack() {
            super("roll back on purpose", null, false, false);
        }
    }

    /** An Emergency's declared interval {@code [declared_at, expected_end_at)}, as the database recorded it. */
    record Declared(Instant start, Instant end) {
    }

    @BeforeEach
    void fixtures() {
        load();
    }

    /** One transaction: each Emergency must commit together with its selling block. */
    private static synchronized void load() {
        if (loaded) {
            return;
        }
        try (Connection owner = DriverManager.getConnection(PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR)) {
            owner.setAutoCommit(false);
            ScriptUtils.executeSqlScript(owner, new ClassPathResource(SCRIPT));
            owner.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Operational overlaps fixtures failed", e);
        }
        loaded = true;
    }

    private <R> R run(Function<CommandContext, R> body) {
        return runner.run(new TestCommand<>(LockSet.empty(), body));
    }

    private Declared declared(UUID emergency) {
        return JdbcClient.create(appDataSource)
                .sql("SELECT declared_at, expected_end_at FROM operations.emergency WHERE id = :id")
                .param("id", emergency)
                .query((rs, n) -> new Declared(rs.getObject("declared_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("expected_end_at", OffsetDateTime.class).toInstant()))
                .single();
    }

    private static Instant at(String utc) {
        return Instant.parse(utc);
    }

    // ---- check 6: Maintenance ----------------------------------------------------------------------------------------

    private boolean maintenance(UUID resource, String start, String end) {
        return run(ctx -> operations.maintenanceOverlaps(ctx, resource, at(start), at(end)));
    }

    @Test
    void maintenanceOverlapsOnlyItsOwnResourceInsideItsHalfOpenPeriod() {
        assertThat(maintenance(MAINTAINED, "2030-03-01T08:00:00Z", "2030-03-01T09:00:00Z")).isTrue();
        assertThat(maintenance(MAINTAINED, "2030-03-01T08:59:00Z", "2030-03-01T10:00:00Z")).isTrue();
        assertThat(maintenance(MAINTAINED, "2030-03-01T07:00:00Z", "2030-03-01T08:01:00Z")).isTrue();
        assertThat(maintenance(MAINTAINED, "2030-03-01T07:00:00Z", "2030-03-01T08:00:00Z")).as("back-to-back before")
                .isFalse();
        assertThat(maintenance(MAINTAINED, "2030-03-01T09:00:00Z", "2030-03-01T10:00:00Z")).as("back-to-back after")
                .isFalse();
        assertThat(maintenance(UNMAINTAINED, "2030-03-01T08:00:00Z", "2030-03-01T09:00:00Z")).as("another Resource")
                .isFalse();
    }

    @Test
    void maintenanceReadSeesTheCommandsOwnUncommittedWrite() {
        assertThatThrownBy(() -> run(ctx -> {
            ctx.jdbc().sql("""
                    INSERT INTO operations.maintenance_window (id, resource_id, period, reason, owner_password_used)
                    VALUES (:id, :resource, tstzrange('2030-03-05 08:00:00+00', '2030-03-05 09:00:00+00', '[)'),
                            'E7-30 in-command maintenance', false)
                    """).param("id", UUID.randomUUID()).param("resource", UNMAINTAINED).update();
            assertThat(operations.maintenanceOverlaps(ctx, UNMAINTAINED, at("2030-03-05T08:30:00Z"),
                    at("2030-03-05T08:45:00Z"))).as("visible inside the command").isTrue();
            throw new RollBack();
        })).isInstanceOf(RollBack.class);

        assertThat(maintenance(UNMAINTAINED, "2030-03-05T08:30:00Z", "2030-03-05T08:45:00Z")).as("rolled back")
                .isFalse();
    }

    // ---- check 7: Venue Closure --------------------------------------------------------------------------------------

    private boolean closure(UUID venue, String start, String end) {
        return run(ctx -> operations.closureOverlaps(ctx, venue, at(start), at(end)));
    }

    @Test
    void closureOverlapsOnlyItsOwnVenueInsideItsHalfOpenPeriod() {
        assertThat(closure(MAINTENANCE_VENUE, "2030-03-02T08:30:00Z", "2030-03-02T08:45:00Z")).isTrue();
        assertThat(closure(MAINTENANCE_VENUE, "2030-03-02T07:00:00Z", "2030-03-02T10:00:00Z")).isTrue();
        assertThat(closure(MAINTENANCE_VENUE, "2030-03-02T07:00:00Z", "2030-03-02T08:00:00Z"))
                .as("back-to-back before").isFalse();
        assertThat(closure(MAINTENANCE_VENUE, "2030-03-02T09:00:00Z", "2030-03-02T10:00:00Z"))
                .as("back-to-back after").isFalse();
        assertThat(closure(OTHER_VENUE, "2030-03-02T08:00:00Z", "2030-03-02T09:00:00Z")).as("another Venue")
                .isFalse();
    }

    // ---- check 5: declared Emergency interval ------------------------------------------------------------------------

    private boolean emergency(UUID venue, UUID resource, Instant start, Instant end) {
        return run(ctx -> operations.declaredEmergencyOverlaps(ctx, venue, resource, start, end));
    }

    @Test
    void aVenueEmergencyBlocksEveryResourceOfItsVenueInsideItsDeclaredInterval() {
        Declared declared = declared(VENUE_EMERGENCY);

        assertThat(emergency(VENUE_EMERGENCY_VENUE, VENUE_EMERGENCY_ROOM, declared.start(), declared.end())).isTrue();
        assertThat(emergency(VENUE_EMERGENCY_VENUE, VENUE_EMERGENCY_ROOM, declared.end().minusSeconds(60),
                declared.end().plusSeconds(3600))).as("partial overlap").isTrue();
        assertThat(emergency(VENUE_EMERGENCY_VENUE, UUID.randomUUID(), declared.start(), declared.end()))
                .as("venue scope: any Resource of the Venue").isTrue();
        assertThat(emergency(OTHER_VENUE, VENUE_EMERGENCY_ROOM, declared.start(), declared.end()))
                .as("another Venue").isFalse();
    }

    @Test
    void anUnresolvedEmergencyIsOnlyItsDeclaredIntervalNeverAnInfiniteOne() {
        Declared declared = declared(VENUE_EMERGENCY);

        assertThat(emergency(VENUE_EMERGENCY_VENUE, VENUE_EMERGENCY_ROOM, declared.end(),
                declared.end().plus(Duration.ofHours(1)))).as("back-to-back after Expected End").isFalse();
        assertThat(emergency(VENUE_EMERGENCY_VENUE, VENUE_EMERGENCY_ROOM, declared.end().plus(Duration.ofDays(1)),
                declared.end().plus(Duration.ofDays(1)).plus(Duration.ofHours(1))))
                .as("far after Expected End, while the Emergency is still ACTIVE").isFalse();
        assertThat(emergency(VENUE_EMERGENCY_VENUE, VENUE_EMERGENCY_ROOM, declared.start().minus(Duration.ofHours(1)),
                declared.start())).as("back-to-back before declared_at").isFalse();
    }

    @Test
    void aResourceEmergencyBlocksOnlyItsOwnResource() {
        Declared declared = declared(RESOURCE_EMERGENCY);

        assertThat(emergency(RESOURCE_EMERGENCY_VENUE, FAILED_ROOM, declared.start(), declared.end())).isTrue();
        assertThat(emergency(RESOURCE_EMERGENCY_VENUE, WORKING_ROOM, declared.start(), declared.end()))
                .as("another Resource of the same Venue").isFalse();
    }

    @Test
    void anEndedEmergencyNoLongerBlocksItsDeclaredInterval() {
        Declared declared = declared(ENDED_EMERGENCY);
        assertThat(emergency(ENDED_EMERGENCY_VENUE, ENDED_EMERGENCY_ROOM, declared.start(), declared.end()))
                .as("while ACTIVE").isTrue();

        // The Emergency end flow on the application role, every trigger on: END the Emergency and close its venue
        // selling block at the Emergency's ended_at, in one command that locks the Venue and its Resource.
        LockSet locks = LockSet.builder().venues(ENDED_EMERGENCY_VENUE).resources(ENDED_EMERGENCY_ROOM).build();
        runner.run(new TestCommand<>(locks, ctx -> {
            ctx.jdbc().sql("UPDATE operations.emergency SET status = 'ENDED' WHERE id = :id")
                    .param("id", ENDED_EMERGENCY).update();
            return ctx.jdbc().sql("""
                    UPDATE venue.venue_selling_block
                       SET active = false,
                           ended_at = (SELECT e.ended_at FROM operations.emergency e WHERE e.id = :id)
                     WHERE emergency_id = :id
                    """).param("id", ENDED_EMERGENCY).update();
        }));

        assertThat(emergency(ENDED_EMERGENCY_VENUE, ENDED_EMERGENCY_ROOM, declared.start(), declared.end()))
                .as("ENDED").isFalse();
    }

    @Test
    void anEmptyOrReversedPeriodIsRefused() {
        Instant start = at("2030-03-01T08:00:00Z");

        assertThatThrownBy(() -> run(ctx -> operations.maintenanceOverlaps(ctx, MAINTAINED, start, start)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(ctx -> operations.closureOverlaps(ctx, MAINTENANCE_VENUE, start,
                start.minusSeconds(1)))).isInstanceOf(IllegalArgumentException.class);
    }

    private static UUID id(int n) {
        return UUID.fromString("e7300300-0000-4000-8000-%012x".formatted(n));
    }
}
