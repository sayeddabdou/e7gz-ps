package ps.e7gz.common.command;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one way a business write reaches the database (arch 04 section 2.1, ADR-026 section 3):
 * <ol>
 *   <li>one {@code READ COMMITTED} transaction, opened here and never joined to a caller's transaction;</li>
 *   <li>{@code tournament.begin_command(...)} or {@code admin.begin_command(...)} first, with the declared lock set -
 *       the database takes the locks in its fixed order and samples the command instant;</li>
 *   <li>the command body;</li>
 *   <li>on SQLSTATE {@code 40001} - raised by {@code begin_command} when the lock set changed while it waited, by a
 *       guard, or at commit - rollback and rerun the whole transaction, bounded and jittered
 *       ({@link CommandRetryProperties}).</li>
 * </ol>
 * Every other exception, guard errors included, propagates exactly as thrown, for the API error mapper. When the
 * retries are exhausted the last {@code 40001} exception propagates, also unchanged.
 */
public class CommandRunner {

    private static final Logger log = LoggerFactory.getLogger(CommandRunner.class);

    private static final String SERIALIZATION_FAILURE = "40001";

    private final Pool app;
    private final Pool admin;
    private final CommandRetryProperties retry;

    public CommandRunner(DataSource appDataSource, DataSource adminDataSource, CommandRetryProperties retry) {
        this.app = new Pool(appDataSource);
        this.admin = new Pool(adminDataSource);
        this.retry = retry;
    }

    public <R> R run(Command<R> command) {
        String name = command.getClass().getName();
        if (command.eligibilityCategory() == null) {
            throw new IllegalStateException(name + " declares no eligibility category (ADR-022)");
        }
        CommandScope scope = command.scope();
        if (scope == null) {
            throw new IllegalStateException(name + " declares no command scope / lock set (arch 04 section 2.1)");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // Joining would break both READ COMMITTED and the retry: a 40001 aborts the caller's transaction too.
            throw new IllegalStateException(name + " must run in its own READ COMMITTED transaction; "
                    + "CommandRunner cannot be called inside another transaction (arch 04 section 2.1)");
        }
        Pool pool = scope instanceof CommandScope.Admin ? admin : app;

        for (int attempt = 1; ; attempt++) {
            int thisAttempt = attempt;
            try {
                return pool.transaction.execute(status -> {
                    Instant commandNow = pool.begin(scope);
                    return command.execute(new CommandContext(commandNow, thisAttempt, pool.jdbcClient));
                });
            } catch (RuntimeException e) {
                if (!isSerializationFailure(e)) {
                    throw e;
                }
                if (attempt >= retry.maxAttempts()) {
                    log.warn("Command {} failed with 40001 on all {} attempts", name, attempt);
                    throw e;
                }
                Duration pause = retry.backoff(attempt, ThreadLocalRandom.current());
                log.debug("Command {} attempt {} failed with 40001; retrying in {} ms", name, attempt, pause.toMillis());
                pauseBeforeRetry(pause, e);
            }
        }
    }

    /** SQLSTATE {@code 40001} anywhere in the cause chain (Spring's translated exceptions keep the driver's cause). */
    static boolean isSerializationFailure(Throwable e) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = e; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof SQLException sql && SERIALIZATION_FAILURE.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static void pauseBeforeRetry(Duration pause, RuntimeException failure) {
        try {
            Thread.sleep(pause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure;
        }
    }

    /** One runtime connection pool with its own transaction manager (arch 04 section 5). */
    private static final class Pool {

        private final TransactionTemplate transaction;
        private final JdbcTemplate jdbcTemplate;
        private final JdbcClient jdbcClient;

        Pool(DataSource dataSource) {
            this.transaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));
            this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
            this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            this.jdbcTemplate = new JdbcTemplate(dataSource);
            this.jdbcClient = JdbcClient.create(jdbcTemplate);
        }

        /** Opens the database command context; returns the command instant it sampled. */
        Instant begin(CommandScope scope) {
            return jdbcTemplate.execute((ConnectionCallback<Instant>) con -> switch (scope) {
                case CommandScope.Shared shared -> {
                    LockSet l = shared.lockSet();
                    try (PreparedStatement statement =
                                 con.prepareStatement("SELECT tournament.begin_command(?, ?, ?, ?, ?)")) {
                        statement.setArray(1, uuids(con, l.users()));
                        statement.setArray(2, uuids(con, l.venues()));
                        statement.setArray(3, uuids(con, l.resources()));
                        statement.setArray(4, uuids(con, l.tournaments()));
                        statement.setArray(5, uuids(con, l.newTournaments()));
                        yield instant(statement);
                    }
                }
                case CommandScope.Admin a -> {
                    try (PreparedStatement statement =
                                 con.prepareStatement("SELECT admin.begin_command(?, ?, ?, ?)")) {
                        statement.setObject(1, a.adminId());
                        statement.setObject(2, a.correlationId());
                        statement.setArray(3, uuids(con, a.lockSet().users()));
                        statement.setArray(4, uuids(con, a.lockSet().venues()));
                        yield instant(statement);
                    }
                }
            });
        }

        private static Array uuids(Connection con, List<UUID> ids) throws SQLException {
            return con.createArrayOf("uuid", ids.toArray());
        }

        private static Instant instant(PreparedStatement statement) throws SQLException {
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getObject(1, OffsetDateTime.class).toInstant();
            }
        }
    }
}
