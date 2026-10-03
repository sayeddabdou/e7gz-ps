package ps.e7gz.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.DatabaseErrorCodes;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-30 / v1.1.32 - {@code operations.guard_emergency_selling_block()} on both tables it guards, with every trigger
 * on. The cases under test write as the application role inside real commands; one supplemental case also shows the
 * guard's own DELETE refusal as the table owner (the application role has no DELETE grant). Before v1.1.32 an UPDATE of an
 * Emergency-linked block failed on the other table's column ({@code record "new" has no field ...}), so no Emergency
 * could be ended; the rules the guard enforces are otherwise unchanged. Its refusals carry no guard identifier, so
 * they are told apart by SQLSTATE {@code P0001} and their unchanged message, as in {@code GuardErrorIdentifiersTest}.
 */
class EmergencySellingBlockGuardTest extends IntegrationTest {

    private static final UUID VENUE_CLOSE = id(0x002);
    private static final UUID RESOURCE_CLOSE_VENUE = id(0x003);
    private static final UUID VENUE_SUBJECT = id(0x004);
    private static final UUID ANOTHER_VENUE = id(0x005);
    private static final UUID RESOURCE_SUBJECT_VENUE = id(0x006);
    private static final UUID VENUE_DELETE = id(0x008);
    private static final UUID RESOURCE_DELETE_VENUE = id(0x009);
    private static final UUID PLAIN_VENUE = id(0x00a);

    private static final UUID RESOURCE_CLOSE = id(0x103);
    private static final UUID FAILED_ROOM = id(0x106);
    private static final UUID OTHER_ROOM = id(0x107);
    private static final UUID RESOURCE_DELETE = id(0x109);
    private static final UUID PLAIN_ROOM = id(0x10a);

    private static final UUID VENUE_CLOSE_EMERGENCY = id(0x702);
    private static final UUID RESOURCE_CLOSE_EMERGENCY = id(0x703);

    private static final UUID VENUE_CLOSE_BLOCK = id(0x802);
    private static final UUID RESOURCE_CLOSE_BLOCK = id(0x803);
    private static final UUID VENUE_SUBJECT_BLOCK = id(0x804);
    private static final UUID RESOURCE_SUBJECT_BLOCK = id(0x806);
    private static final UUID VENUE_DELETE_BLOCK = id(0x808);
    private static final UUID RESOURCE_DELETE_BLOCK = id(0x809);

    private static final String SCRIPT = "ps/e7gz/operations/emergency-selling-block-guard-fixtures.sql";
    private static boolean loaded;

    @Autowired
    CommandRunner runner;

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

    /** A block's state as committed. */
    record Block(boolean active, OffsetDateTime endedAt) {
    }

    @BeforeEach
    void fixtures() {
        load();
    }

    /** One transaction, every trigger on: each Emergency commits together with its selling block. */
    private static synchronized void load() {
        if (loaded) {
            return;
        }
        try (Connection owner = DriverManager.getConnection(PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR)) {
            owner.setAutoCommit(false);
            ScriptUtils.executeSqlScript(owner, new ClassPathResource(SCRIPT));
            owner.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Emergency selling block guard fixtures failed", e);
        }
        loaded = true;
    }

    private void run(LockSet locks, Function<CommandContext, ?> body) {
        runner.run(new TestCommand<>(locks, body));
    }

    private static LockSet venue(UUID venue, UUID... resources) {
        return LockSet.builder().venues(venue).resources(resources).build();
    }

    private Block block(String table, UUID id) {
        return runner.run(new TestCommand<>(LockSet.empty(), ctx -> ctx.jdbc()
                .sql("SELECT active, ended_at FROM " + table + " WHERE id = :id").param("id", id)
                .query((rs, n) -> new Block(rs.getBoolean("active"), rs.getObject("ended_at", OffsetDateTime.class)))
                .single()));
    }

    private OffsetDateTime endedAt(UUID emergency) {
        return runner.run(new TestCommand<>(LockSet.empty(), ctx -> ctx.jdbc()
                .sql("SELECT ended_at FROM operations.emergency WHERE id = :id").param("id", emergency)
                .query(OffsetDateTime.class).single()));
    }

    /** Ends the Emergency and closes its block at the Emergency's ended_at, in one command (the Emergency end flow). */
    private void endEmergency(LockSet locks, UUID emergency, String blockTable) {
        run(locks, ctx -> {
            ctx.jdbc().sql("UPDATE operations.emergency SET status = 'ENDED' WHERE id = :id").param("id", emergency)
                    .update();
            return ctx.jdbc().sql("UPDATE " + blockTable + """
                     SET active = false,
                         ended_at = (SELECT e.ended_at FROM operations.emergency e WHERE e.id = :id)
                     WHERE emergency_id = :id
                    """).param("id", emergency).update();
        });
    }

    private static void assertGuardRefusal(Throwable failure, String message) {
        assertThat(failure).isInstanceOf(DataAccessException.class);
        assertThat(DatabaseErrorCodes.serverError(failure)).hasValueSatisfying(error -> {
            assertThat(error.getSQLState()).isEqualTo(DatabaseErrorCodes.RAISE_EXCEPTION);
            assertThat(error.getMessage()).isEqualTo(message);
            assertThat(error.getRoutine()).isNotNull();
        });
    }

    // ---- 1, 2 and 5: an Emergency-linked block closes normally, and a closed block cannot reopen ---------------------

    @Test
    void aVenueEmergencyBlockClosesWithItsEmergencyAndCannotBeReopened() {
        LockSet locks = venue(VENUE_CLOSE);

        endEmergency(locks, VENUE_CLOSE_EMERGENCY, "venue.venue_selling_block");

        OffsetDateTime ended = endedAt(VENUE_CLOSE_EMERGENCY);
        assertThat(ended).isNotNull();
        assertThat(block("venue.venue_selling_block", VENUE_CLOSE_BLOCK)).isEqualTo(new Block(false, ended));

        Throwable reopen = catchThrowable(() -> run(locks, ctx -> ctx.jdbc().sql("""
                UPDATE venue.venue_selling_block SET active = true, ended_at = NULL WHERE id = :id
                """).param("id", VENUE_CLOSE_BLOCK).update()));
        assertGuardRefusal(reopen, "Closed Emergency selling block cannot be reactivated or rewritten");
        assertThat(block("venue.venue_selling_block", VENUE_CLOSE_BLOCK)).isEqualTo(new Block(false, ended));
    }

    @Test
    void aResourceEmergencyBlockClosesWithItsEmergencyAndCannotBeReopened() {
        LockSet locks = venue(RESOURCE_CLOSE_VENUE, RESOURCE_CLOSE);

        endEmergency(locks, RESOURCE_CLOSE_EMERGENCY, "resource.resource_selling_block");

        OffsetDateTime ended = endedAt(RESOURCE_CLOSE_EMERGENCY);
        assertThat(ended).isNotNull();
        assertThat(block("resource.resource_selling_block", RESOURCE_CLOSE_BLOCK)).isEqualTo(new Block(false, ended));

        Throwable reopen = catchThrowable(() -> run(locks, ctx -> ctx.jdbc().sql("""
                UPDATE resource.resource_selling_block SET active = true, ended_at = NULL WHERE id = :id
                """).param("id", RESOURCE_CLOSE_BLOCK).update()));
        assertGuardRefusal(reopen, "Closed Emergency selling block cannot be reactivated or rewritten");
        assertThat(block("resource.resource_selling_block", RESOURCE_CLOSE_BLOCK)).isEqualTo(new Block(false, ended));
    }

    // ---- 3, 4: the subject of an Emergency-linked block cannot move ------------------------------------------------

    @Test
    void theVenueOfAnEmergencyVenueBlockCannotChange() {
        // The guard refuses a block that no longer matches its Emergency before it reaches its own immutability check.
        Throwable failure = catchThrowable(() -> run(venue(VENUE_SUBJECT), ctx -> ctx.jdbc().sql("""
                UPDATE venue.venue_selling_block SET venue_id = :other WHERE id = :id
                """).param("other", ANOTHER_VENUE).param("id", VENUE_SUBJECT_BLOCK).update()));

        assertGuardRefusal(failure, "Venue selling block must reference a VENUE Emergency for the same Venue");
        assertThat(block("venue.venue_selling_block", VENUE_SUBJECT_BLOCK).active()).isTrue();
    }

    @Test
    void theResourceOfAnEmergencyResourceBlockCannotChange() {
        Throwable failure = catchThrowable(() -> run(venue(RESOURCE_SUBJECT_VENUE, FAILED_ROOM, OTHER_ROOM),
                ctx -> ctx.jdbc().sql("""
                        UPDATE resource.resource_selling_block SET resource_id = :other WHERE id = :id
                        """).param("other", OTHER_ROOM).param("id", RESOURCE_SUBJECT_BLOCK).update()));

        assertGuardRefusal(failure, "Resource selling block must reference a RESOURCE Emergency for the same Resource");
        assertThat(block("resource.resource_selling_block", RESOURCE_SUBJECT_BLOCK).active()).isTrue();
    }

    @Test
    void theStartOfAnEmergencyBlockCannotChangeOnEitherTable() {
        Throwable venue = catchThrowable(() -> run(venue(VENUE_SUBJECT), ctx -> ctx.jdbc().sql("""
                UPDATE venue.venue_selling_block SET started_at = started_at - interval '1 minute' WHERE id = :id
                """).param("id", VENUE_SUBJECT_BLOCK).update()));
        Throwable resource = catchThrowable(() -> run(venue(RESOURCE_SUBJECT_VENUE, FAILED_ROOM), ctx -> ctx.jdbc().sql("""
                UPDATE resource.resource_selling_block SET started_at = started_at - interval '1 minute' WHERE id = :id
                """).param("id", RESOURCE_SUBJECT_BLOCK).update()));

        assertGuardRefusal(venue, "Emergency selling-block identity/start/cause are immutable");
        assertGuardRefusal(resource, "Emergency selling-block identity/start/cause are immutable");
    }

    @Test
    void anActiveEmergencyBlockCannotCarryAnEndOnEitherTable() {
        Throwable venue = catchThrowable(() -> run(venue(VENUE_SUBJECT), ctx -> ctx.jdbc().sql("""
                UPDATE venue.venue_selling_block SET ended_at = started_at WHERE id = :id
                """).param("id", VENUE_SUBJECT_BLOCK).update()));
        Throwable resource = catchThrowable(() -> run(venue(RESOURCE_SUBJECT_VENUE, FAILED_ROOM), ctx -> ctx.jdbc().sql("""
                UPDATE resource.resource_selling_block SET ended_at = started_at WHERE id = :id
                """).param("id", RESOURCE_SUBJECT_BLOCK).update()));

        assertGuardRefusal(venue, "Active selling block cannot have ended_at");
        assertGuardRefusal(resource, "Active selling block cannot have ended_at");
    }

    // ---- 6: an Emergency-linked block is never deleted ---------------------------------------------------------------

    @Test
    void theApplicationRoleCannotDeleteASellingBlockAtAll() {
        Throwable venue = catchThrowable(() -> run(venue(VENUE_DELETE), ctx -> ctx.jdbc()
                .sql("DELETE FROM venue.venue_selling_block WHERE id = :id").param("id", VENUE_DELETE_BLOCK).update()));
        Throwable resource = catchThrowable(() -> run(venue(RESOURCE_DELETE_VENUE, RESOURCE_DELETE), ctx -> ctx.jdbc()
                .sql("DELETE FROM resource.resource_selling_block WHERE id = :id").param("id", RESOURCE_DELETE_BLOCK)
                .update()));

        for (Throwable failure : new Throwable[] {venue, resource}) {
            assertThat(DatabaseErrorCodes.serverError(failure)).hasValueSatisfying(error ->
                    assertThat(error.getSQLState()).as("insufficient_privilege").isEqualTo("42501"));
        }
    }

    @Test
    void theGuardRefusesToDeleteAnEmergencyBlockEvenForTheTableOwner() throws SQLException {
        // The owner role, with every trigger on (not the superuser, no replication role): the guard's own refusal.
        assertThat(ownerDeleteFailure("venue.venue_selling_block", VENUE_DELETE_BLOCK).getSQLState())
                .isEqualTo(DatabaseErrorCodes.RAISE_EXCEPTION);
        assertThat(ownerDeleteFailure("venue.venue_selling_block", VENUE_DELETE_BLOCK).getMessage())
                .isEqualTo("Emergency selling-block history may not be deleted");
        assertThat(ownerDeleteFailure("resource.resource_selling_block", RESOURCE_DELETE_BLOCK).getMessage())
                .isEqualTo("Emergency selling-block history may not be deleted");
    }

    private static ServerErrorMessage ownerDeleteFailure(String table, UUID block) throws SQLException {
        try (Connection owner = DriverManager.getConnection(PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR);
             Statement statement = owner.createStatement()) {
            owner.setAutoCommit(false);
            try {
                statement.executeUpdate("DELETE FROM " + table + " WHERE id = '" + block + "'");
                throw new AssertionError("DELETE of " + block + " was not refused");
            } catch (PSQLException e) {
                return e.getServerErrorMessage();
            } finally {
                owner.rollback();
            }
        }
    }

    // ---- 7: a block with no Emergency is not the guard's business --------------------------------------------------

    @Test
    void aPlainBlockStillOpensAndClosesNormallyOnBothTables() {
        UUID venueBlock = UUID.randomUUID();
        UUID resourceBlock = UUID.randomUUID();
        OffsetDateTime started = OffsetDateTime.parse("2030-01-01T10:00:00Z");
        OffsetDateTime ended = OffsetDateTime.parse("2030-01-02T10:00:00Z");
        LockSet locks = venue(PLAIN_VENUE, PLAIN_ROOM);

        run(locks, ctx -> {
            ctx.jdbc().sql("""
                    INSERT INTO venue.venue_selling_block (id, venue_id, cause, active, started_at)
                    VALUES (:id, :venue, 'E7-30 plain block', true, :started)
                    """).param("id", venueBlock).param("venue", PLAIN_VENUE).param("started", started).update();
            return ctx.jdbc().sql("""
                    INSERT INTO resource.resource_selling_block (id, resource_id, cause, active, started_at)
                    VALUES (:id, :resource, 'E7-30 plain block', true, :started)
                    """).param("id", resourceBlock).param("resource", PLAIN_ROOM).param("started", started).update();
        });
        run(locks, ctx -> {
            ctx.jdbc().sql("UPDATE venue.venue_selling_block SET active = false, ended_at = :ended WHERE id = :id")
                    .param("ended", ended).param("id", venueBlock).update();
            return ctx.jdbc().sql("UPDATE resource.resource_selling_block SET active = false, ended_at = :ended WHERE id = :id")
                    .param("ended", ended).param("id", resourceBlock).update();
        });

        assertThat(block("venue.venue_selling_block", venueBlock)).isEqualTo(new Block(false, ended));
        assertThat(block("resource.resource_selling_block", resourceBlock)).isEqualTo(new Block(false, ended));
    }

    private static UUID id(int n) {
        return UUID.fromString("e7300400-0000-4000-8000-%012x".formatted(n));
    }
}
