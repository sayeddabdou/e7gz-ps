package ps.e7gz.admin.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ps.e7gz.admin.api.AdminAlerts;
import ps.e7gz.admin.api.NewAdminAlert;
import ps.e7gz.common.command.CommandContext;

/**
 * Raises alerts through {@code admin.raise_alert}, the one producer of {@code admin.admin_alert} (v1.1.36; NFR-29,
 * arch 11 section 5.4; E7-56). Either runtime role may call it; the table rules stay in the database.
 *
 * <p>An alert "of its own" ({@link #raiseAfterRollback}, the dead-letter and token-reuse alerts) runs on a fresh
 * connection from the app pool with its own commit, outside every Spring-bound transaction - the same shape as the
 * outbox dispatcher - so a rollback of the caller cannot touch it.
 */
@Component
class JdbcAdminAlerts implements AdminAlerts {

    private static final Logger log = LoggerFactory.getLogger(JdbcAdminAlerts.class);

    static final String RAISE = """
            SELECT admin.raise_alert(:kind, :severity, :subjectType, :subjectId, CAST(:payload AS jsonb), :correlation)
            """;

    /** Serialises "raise once while open" for one subject across workers (a transaction advisory lock). */
    private static final String LOCK_SUBJECT = "SELECT pg_advisory_xact_lock(hashtext(:key))";

    /**
     * The same defect already raised and not yet acknowledged: same kind, subject and payload, whichever request or job
     * attempt found it (the correlation ID is left out of the comparison).
     */
    private static final String OPEN_DUPLICATE = """
            SELECT EXISTS (
                SELECT 1 FROM admin.admin_alert
                 WHERE kind = :kind AND subject_type = :subjectType AND subject_id = :subjectId
                   AND coalesce(payload, '{}') - 'correlationId'
                       = coalesce(CAST(:payload AS jsonb), '{}') - 'correlationId'
                   AND acknowledged_at IS NULL)
            """;

    private final DataSource appDataSource;
    private final ObjectMapper json;

    JdbcAdminAlerts(DataSource appDataSource, ObjectMapper json) {
        this.appDataSource = appDataSource;
        this.json = json;
    }

    @Override
    public UUID raise(CommandContext command, NewAdminAlert alert) {
        Objects.requireNonNull(command, "command");
        return raise(command.jdbc(), alert);
    }

    /** In the transaction of {@code jdbc} (a command's, or the outbox dispatcher's). */
    UUID raise(JdbcClient jdbc, NewAdminAlert alert) {
        Objects.requireNonNull(alert, "alert");
        return jdbc.sql(RAISE)
                .param("kind", alert.kind().name())
                .param("severity", alert.severity().name())
                .param("subjectType", alert.subjectType())
                .param("subjectId", alert.subjectId())
                .param("payload", payload(alert))
                .param("correlation", alert.correlationId())
                .query(UUID.class).single();
    }

    @Override
    public void raiseAfterRollback(NewAdminAlert alert) {
        Objects.requireNonNull(alert, "alert");
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            raiseOnceAlone(alert);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    raiseOnceAlone(alert);
                }
            }
        });
    }

    /**
     * In a transaction of its own; at most once while the same defect's alert is open (the bounded retries of a failing
     * job, or another request on the same broken booking, find the first one). Never throws: a failing alert is logged
     * at ERROR.
     */
    void raiseOnceAlone(NewAdminAlert alert) {
        try {
            alone(jdbc -> {
                String payload = payload(alert);
                jdbc.sql(LOCK_SUBJECT)
                        .param("key", alert.kind().name() + ":" + alert.subjectType() + ":" + alert.subjectId())
                        .query().singleRow();
                boolean open = Boolean.TRUE.equals(jdbc.sql(OPEN_DUPLICATE)
                        .param("kind", alert.kind().name())
                        .param("subjectType", alert.subjectType())
                        .param("subjectId", alert.subjectId())
                        .param("payload", payload)
                        .query(Boolean.class).single());
                if (!open) {
                    raise(jdbc, alert);
                }
            });
        } catch (RuntimeException failure) {
            failed(alert, failure);
        }
    }

    /** In a transaction of its own, unconditionally. Never throws: a failing alert is logged at ERROR. */
    void raiseAlone(NewAdminAlert alert) {
        try {
            alone(jdbc -> raise(jdbc, alert));
        } catch (RuntimeException failure) {
            failed(alert, failure);
        }
    }

    private void alone(Consumer<JdbcClient> body) {
        try (Connection connection = appDataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                body.accept(JdbcClient.create(new SingleConnectionDataSource(connection, true)));
                connection.commit();
            } catch (RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Admin alert: no database connection", e);
        }
    }

    private static void failed(NewAdminAlert alert, RuntimeException failure) {
        log.error("Admin alert {} {} on {} {} could not be raised (correlationId={})", alert.severity(),
                alert.kind(), alert.subjectType(), alert.subjectId(), alert.correlationId(), failure);
    }

    private String payload(NewAdminAlert alert) {
        try {
            return json.writeValueAsString(alert.payload());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Admin alert payload is not JSON", e);
        }
    }
}
