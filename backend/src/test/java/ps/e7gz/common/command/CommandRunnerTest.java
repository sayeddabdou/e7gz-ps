package ps.e7gz.common.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.PostgresTestContainer.APP_RUNTIME;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.DatabaseErrorCodes;
import ps.e7gz.support.CommitFault;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-15 - the command protocol of arch 04 section 2.1 / ADR-026 section 3 against the real migrated database.
 */
@TestPropertySource(properties = {
        "e7gz.command.retry.max-attempts=3",
        "e7gz.command.retry.initial-backoff=1ms",
        "e7gz.command.retry.max-backoff=5ms"})
class CommandRunnerTest extends IntegrationTest {

    private static final String RAISE_40001 =
            "DO $$ BEGIN RAISE EXCEPTION USING ERRCODE = '40001', MESSAGE = 'test serialization failure'; END $$";

    @Autowired
    CommandRunner runner;

    @Autowired
    DataSource appDataSource;

    /** A command whose body is a lambda; the category and scope are declared up front like any command. */
    record TestCommand<R>(EligibilityCategory eligibilityCategory, CommandScope scope,
                          Function<CommandContext, R> body) implements Command<R> {

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }

    private static <R> TestCommand<R> command(CommandScope scope, Function<CommandContext, R> body) {
        return new TestCommand<>(EligibilityCategory.C_INTERNAL_VENUE_OPERATION, scope, body);
    }

    private static String xactId(CommandContext context) {
        return context.jdbc().sql("SELECT pg_current_xact_id()::text").query(String.class).single();
    }

    @Test
    void runsTheBodyInOneReadCommittedTransactionAtTheDatabaseCommandInstant() {
        List<String> seen = runner.run(command(CommandScope.shared(LockSet.empty()), context -> {
            Instant dbCommandNow = context.jdbc().sql("SELECT tournament.command_now()")
                    .query(OffsetDateTime.class).single().toInstant();
            assertThat(context.commandNow()).isEqualTo(dbCommandNow);
            assertThat(context.attempt()).isEqualTo(1);
            String first = xactId(context);
            String isolation = context.jdbc().sql("SELECT current_setting('transaction_isolation')")
                    .query(String.class).single();
            return List.of(isolation, first, xactId(context), context.jdbc()
                    .sql("SELECT current_user").query(String.class).single());
        }));

        assertThat(seen.get(0)).isEqualTo("read committed");
        assertThat(seen.get(1)).as("every statement runs in the same transaction").isEqualTo(seen.get(2));
        assertThat(seen.get(3)).isEqualTo(APP_RUNTIME);
    }

    @Test
    void aChangedLockSetRaises40001AndTheWholeCommandIsRetriedInANewTransaction() throws Exception {
        UUID resource;
        UUID otherVenue;
        try (Connection owner = CommandFixtures.ownerConnection()) {
            UUID area = CommandFixtures.area(owner);
            UUID venue = CommandFixtures.venue(owner, area);
            otherVenue = CommandFixtures.venue(owner, area);
            resource = CommandFixtures.resource(owner, venue);
        }

        AtomicInteger bodyRuns = new AtomicInteger();
        List<String> transactions = new ArrayList<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection concurrent = CommandFixtures.ownerConnection()) {
            // A concurrent writer holds the Resource and is about to move it to another Venue.
            concurrent.setAutoCommit(false);
            CommandFixtures.update(concurrent, "SELECT 1 FROM resource.resource WHERE id = ? FOR UPDATE", resource);
            int concurrentPid = backendPid(concurrent);

            CompletableFuture<Integer> result = CompletableFuture.supplyAsync(() -> runner.run(command(
                    CommandScope.shared(LockSet.builder().resources(resource).build()), context -> {
                        bodyRuns.incrementAndGet();
                        synchronized (transactions) {
                            transactions.add(xactId(context));
                        }
                        return context.attempt();
                    })), executor);

            // begin_command discovered Venue V1, locked it, and now waits for the Resource.
            awaitBlockedBy(concurrentPid);
            CommandFixtures.update(concurrent, "UPDATE resource.resource SET venue_id = ? WHERE id = ?", otherVenue, resource);
            concurrent.commit();

            assertThat(result.get(30, TimeUnit.SECONDS)).as("succeeded on the second attempt").isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
        assertThat(bodyRuns).as("the 40001 came from begin_command, before the body").hasValue(1);
        assertThat(transactions).hasSize(1);
    }

    @Test
    void aSerializationFailureInTheBodyRerunsTheCompleteBodyInANewTransaction() {
        List<String> steps = new ArrayList<>();
        List<String> transactions = new ArrayList<>();
        List<Instant> instants = new ArrayList<>();

        String result = runner.run(command(CommandScope.shared(LockSet.empty()), context -> {
            steps.add("step 1, attempt " + context.attempt());
            transactions.add(xactId(context));
            instants.add(context.commandNow());
            if (context.attempt() == 1) {
                context.jdbc().sql(RAISE_40001).update();
            }
            steps.add("step 2, attempt " + context.attempt());
            return "done";
        }));

        assertThat(result).isEqualTo("done");
        assertThat(steps).containsExactly("step 1, attempt 1", "step 1, attempt 2", "step 2, attempt 2");
        assertThat(transactions).hasSize(2).doesNotHaveDuplicates();
        assertThat(instants.get(1)).as("a new command instant for the new transaction").isAfterOrEqualTo(instants.get(0));
    }

    @Test
    void retryIsBoundedAndTheLast40001SurfacesUnwrapped() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> runner.run(command(CommandScope.shared(LockSet.empty()), context -> {
            attempts.incrementAndGet();
            return context.jdbc().sql(RAISE_40001).update();
        })))
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(CommandRunner.isSerializationFailure(e)).isTrue())
                .rootCause().hasMessageContaining("test serialization failure");
        assertThat(attempts).as("e7gz.command.retry.max-attempts").hasValue(3);
    }

    /** A {@link TestCommand} that also records {@link Command#beforeAttempt}, and whether a transaction was open then. */
    record HookedCommand<R>(CommandScope scope, List<String> events, Function<CommandContext, R> body)
            implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public void beforeAttempt(int attempt) {
            events.add("before " + attempt + (TransactionSynchronizationManager.isActualTransactionActive()
                    ? " in a transaction" : ""));
        }

        @Override
        public R execute(CommandContext context) {
            events.add("body " + context.attempt());
            return body.apply(context);
        }
    }

    /**
     * E7-114 (Q2): {@code beforeAttempt} runs exactly once at the start of every attempt, the 40001 reruns included,
     * before that attempt's transaction - so before {@code begin_command} - and the body still runs once per attempt.
     */
    @Test
    void beforeAttemptRunsOnceAtTheStartOfEveryAttemptBeforeItsTransaction() {
        List<String> events = new ArrayList<>();

        String result = runner.run(new HookedCommand<>(CommandScope.shared(LockSet.empty()), events, context -> {
            if (context.attempt() < 3) {
                context.jdbc().sql(RAISE_40001).update();
            }
            return "done";
        }));

        assertThat(result).isEqualTo("done");
        assertThat(events).containsExactly("before 1", "body 1", "before 2", "body 2", "before 3", "body 3");
    }

    /** A {@code beforeAttempt} that throws ends the run before any transaction: no body, no retry. */
    @Test
    void aBeforeAttemptThatThrowsEndsTheRunBeforeItsTransaction() {
        List<String> events = new ArrayList<>();
        HookedCommand<String> command = new HookedCommand<>(CommandScope.shared(LockSet.empty()), events,
                context -> "never");

        assertThatThrownBy(() -> runner.run(new Command<String>() {
            @Override
            public EligibilityCategory eligibilityCategory() {
                return command.eligibilityCategory();
            }

            @Override
            public CommandScope scope() {
                return command.scope();
            }

            @Override
            public void beforeAttempt(int attempt) {
                command.beforeAttempt(attempt);
                throw new IllegalStateException("refused before attempt " + attempt);
            }

            @Override
            public String execute(CommandContext context) {
                return command.execute(context);
            }
        })).isInstanceOf(IllegalStateException.class).hasMessage("refused before attempt 1");
        assertThat(events).containsExactly("before 1");
    }

    @Test
    void guardExceptionsBubbleUpUnchangedAndAreNotRetried() {
        AtomicInteger attempts = new AtomicInteger();

        // A real DB guard: a second begin_command in the same transaction is refused (P0001).
        assertThatThrownBy(() -> runner.run(command(CommandScope.shared(LockSet.empty()), context -> {
            attempts.incrementAndGet();
            return context.jdbc().sql("SELECT tournament.begin_command('{}', '{}', '{}', '{}')")
                    .query(OffsetDateTime.class).single();
        })))
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("P0001"))
                .rootCause().hasMessageContaining("Exactly one Tournament command per transaction");
        assertThat(attempts).hasValue(1);
    }

    /**
     * A guard that fires only at COMMIT (a deferred constraint trigger) fails the run with the same DataAccessException
     * a failed statement gets, its SQLSTATE and guard identifier kept, so the API answers its code - never a
     * TransactionSystemException answered 500 (PO decision 25 Oct 2026, E7-114 follow-up). Not retried.
     */
    @Test
    void aGuardFailingTheCommitBubblesUpAsADataAccessExceptionWithItsIdentifierAndIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();

        try (CommitFault fault = CommitFault.scratch("gd_reservation_conflict_booking")) {
            assertThatThrownBy(() -> runner.run(command(CommandScope.shared(LockSet.empty()), context -> {
                attempts.incrementAndGet();
                fault.armIn(context);
                return "returned";
            })))
                    .isInstanceOf(DataAccessException.class)
                    .satisfies(e -> assertThat(sqlState(e)).isEqualTo("P0001"))
                    .satisfies(e -> assertThat(DatabaseErrorCodes.translate(e))
                            .contains(ApiErrorCode.SLOT_NO_LONGER_AVAILABLE));
        }
        assertThat(attempts).hasValue(1);
    }

    @Test
    void theDatabaseRefusesACommandUnderRepeatableRead() throws SQLException {
        try (Connection app = DriverManager.getConnection(PostgresTestContainer.jdbcUrl(DATABASE), APP_RUNTIME, APP_RUNTIME)) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            assertThatThrownBy(() -> {
                try (PreparedStatement statement =
                             app.prepareStatement("SELECT tournament.begin_command('{}', '{}', '{}', '{}')")) {
                    statement.executeQuery();
                }
            })
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Tournament command requires READ COMMITTED");
            app.rollback();
        }
    }

    @Test
    void theRunnerRefusesToJoinAnOuterTransaction() {
        TransactionTemplate outer = new TransactionTemplate(new DataSourceTransactionManager(appDataSource));
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        AtomicInteger bodyRuns = new AtomicInteger();

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> runner.run(
                command(CommandScope.shared(LockSet.empty()), context -> bodyRuns.incrementAndGet()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("its own READ COMMITTED transaction");
        assertThat(bodyRuns).hasValue(0);
    }

    @Test
    void aCommandWithoutAnEligibilityCategoryCannotRun() {
        AtomicInteger bodyRuns = new AtomicInteger();

        assertThatThrownBy(() -> runner.run(new TestCommand<>(null, CommandScope.shared(LockSet.empty()),
                context -> bodyRuns.incrementAndGet())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eligibility category");
        assertThat(bodyRuns).hasValue(0);
    }

    @Test
    void anAdminCommandOpensAdminBeginCommandOnTheAdminConnection() throws SQLException {
        UUID adminId;
        UUID venue;
        try (Connection owner = CommandFixtures.ownerConnection()) {
            adminId = CommandFixtures.admin(owner);
            venue = CommandFixtures.venue(owner, CommandFixtures.area(owner));
        }
        UUID correlationId = UUID.randomUUID();

        List<Object> seen = runner.run(command(
                CommandScope.admin(adminId, correlationId, LockSet.builder().venues(venue).build()), context -> {
                    var row = context.jdbc().sql("SELECT admin_id, correlation_id, command_at FROM admin.command_context()")
                            .query((rs, n) -> List.<Object>of(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                                    rs.getObject(3, OffsetDateTime.class).toInstant()))
                            .single();
                    assertThat(row.get(2)).isEqualTo(context.commandNow());
                    List<Object> all = new ArrayList<>(row);
                    all.add(context.jdbc().sql("SELECT current_user").query(String.class).single());
                    return all;
                }));

        assertThat(seen).containsExactly(adminId, correlationId, seen.get(2), PostgresTestContainer.ADMIN_RUNTIME);
    }

    /** E7-23: no admin exists yet, so no admin.begin_command; admin connection plus the bootstrap advisory lock. */
    @Test
    void theFirstAdminBootstrapScopeRunsOnTheAdminConnectionUnderItsAdvisoryLock() {
        List<Object> seen = runner.run(command(CommandScope.firstAdminBootstrap(), context -> List.of(
                context.jdbc().sql("SELECT current_user").query(String.class).single(),
                context.jdbc().sql("""
                                SELECT count(*) FROM pg_locks
                                 WHERE locktype = 'advisory' AND pid = pg_backend_pid() AND granted""")
                        .query(Long.class).single(),
                context.commandNow())));

        assertThat(seen.get(0)).isEqualTo(PostgresTestContainer.ADMIN_RUNTIME);
        assertThat(seen.get(1)).isEqualTo(1L);
        assertThat(seen.get(2)).isNotNull();
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_backend_pid()");
             ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Waits until some session is blocked on a lock held by {@code pid}. */
    private static void awaitBlockedBy(int pid) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        try (Connection monitor = CommandFixtures.ownerConnection();
             PreparedStatement statement = monitor.prepareStatement(
                     "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))")) {
            statement.setInt(1, pid);
            while (System.nanoTime() < deadline) {
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        return;
                    }
                }
                TimeUnit.MILLISECONDS.sleep(20);
            }
        }
        throw new AssertionError("The command never waited for the concurrent writer's lock");
    }
}
