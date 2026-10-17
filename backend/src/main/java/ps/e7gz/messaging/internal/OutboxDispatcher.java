package ps.e7gz.messaging.internal;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.DeadLetterAlerts;
import ps.e7gz.messaging.api.OutboxConsumer;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.messaging.api.PermanentDeliveryFailure;

/**
 * The outbox dispatcher of one worker thread (ADR-004, arch 08 sections 4.1-4.3). One call of {@link #dispatchNext()}
 * delivers one event, in one short {@code READ COMMITTED} transaction of its own:
 * <ol>
 *   <li>lock the oldest due {@code PENDING} event of a type some consumer takes ({@code ORDER BY id FOR UPDATE SKIP
 *       LOCKED}), so no other dispatcher - thread or process - delivers it at the same time;</li>
 *   <li>run every consumer of its type, with the event's correlation ID open on the thread;</li>
 *   <li>record the result through the {@code technical.outbox_*} functions: all accepted - {@code DISPATCHED}; a
 *       failure - back to {@code PENDING} after the back-off of arch 08 section 4.3; a {@link PermanentDeliveryFailure}
 *       or the last attempt failed - {@code DEAD_LETTER}, which is final (logged at ERROR, Sev-2, and one SEV_2 admin
 *       alert in the same transaction, E7-56; PO decisions 9 and 12 Oct 2026);</li>
 *   <li>commit.</li>
 * </ol>
 * A dispatcher that dies mid-delivery leaves the event {@code PENDING} with the same attempt count: the lock goes with
 * its connection and the next poll delivers it again (at least once; consumers are safe to run twice). The transaction is
 * not bound to the thread, so a consumer can run its own command through the {@code CommandRunner}.
 *
 * <p>Order (PO decision 9 Oct 2026): events are taken in {@code id} order, but an event waiting for its retry does not
 * hold back later events of the same aggregate.
 */
final class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int MAX_ERROR_LENGTH = 1000;

    private final String name;
    private final ConsumerRegistry consumers;
    private final JdbcOutboxStore store;
    private final DataSource dataSource;
    private final OutboxProperties properties;
    private final List<DeadLetterAlerts> deadLetterAlerts;

    OutboxDispatcher(String name, ConsumerRegistry consumers, JdbcOutboxStore store, DataSource appDataSource,
                     OutboxProperties properties) {
        this(name, consumers, store, appDataSource, properties, List.of());
    }

    OutboxDispatcher(String name, ConsumerRegistry consumers, JdbcOutboxStore store, DataSource appDataSource,
                     OutboxProperties properties, List<DeadLetterAlerts> deadLetterAlerts) {
        this.name = name;
        this.consumers = consumers;
        this.store = store;
        this.dataSource = appDataSource;
        this.properties = properties;
        this.deadLetterAlerts = List.copyOf(deadLetterAlerts);
    }

    String name() {
        return name;
    }

    /** Delivers the next due event; false when none was due for this dispatcher (the caller may wait for a tick). */
    boolean dispatchNext() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The outbox dispatcher runs outside any transaction");
        }
        if (consumers.types().isEmpty()) {
            return false;
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                boolean delivered = dispatchNext(JdbcClient.create(new SingleConnectionDataSource(connection, true)));
                connection.commit();
                return delivered;
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Outbox dispatcher " + name + " lost its database connection", e);
        }
    }

    private boolean dispatchNext(JdbcClient jdbc) {
        Optional<OutboxEvent> next = store.lockNextDue(jdbc, consumers.types());
        if (next.isEmpty()) {
            return false;
        }
        OutboxEvent event = next.get();
        try (CorrelationId.Scope ignored = CorrelationId.open(event.correlationId())) {
            RuntimeException failure = deliver(event);
            int attempt = event.attempt() + 1;
            if (failure == null) {
                store.markDispatched(jdbc, event.id());
            } else if (failure instanceof PermanentDeliveryFailure || attempt >= properties.maxAttempts()) {
                String lastError = describe(failure);
                store.markDeadLetter(jdbc, event.id(), lastError);
                log.error("Outbox {} {} DEAD_LETTER after attempt {} (Sev-2)", event.eventType(), event.id(), attempt,
                        failure);
                alertDeadLetter(jdbc, event, lastError);
            } else {
                store.markRetry(jdbc, event.id(), properties.delayAfter(attempt), describe(failure));
                log.warn("Outbox {} {} attempt {} failed; retried in {}", event.eventType(), event.id(), attempt,
                        properties.delayAfter(attempt), failure);
            }
        }
        return true;
    }

    /**
     * Runs every consumer of the event, each even when an earlier one failed; returns the first permanent failure, else
     * the first failure, else null.
     */
    private RuntimeException deliver(OutboxEvent event) {
        List<OutboxConsumer> takers = consumers.consumers(event.eventType());
        RuntimeException failure = null;
        for (OutboxConsumer consumer : takers) {
            try {
                consumer.handle(event);
            } catch (RuntimeException e) {
                if (failure == null || (e instanceof PermanentDeliveryFailure
                        && !(failure instanceof PermanentDeliveryFailure))) {
                    failure = e;
                }
            }
        }
        return failure;
    }

    /**
     * The Platform Admin alert of a dead letter (E7-56, PO decision 12 Oct 2026): in this transaction, so it commits
     * with the DEAD_LETTER mark - once per event. Behind a savepoint: an alert that fails is logged and rolled back alone,
     * and the event still reaches DEAD_LETTER (it must never go back to being delivered because its alert failed).
     */
    private void alertDeadLetter(JdbcClient jdbc, OutboxEvent event, String lastError) {
        for (DeadLetterAlerts alerts : deadLetterAlerts) {
            jdbc.sql("SAVEPOINT dead_letter_alert").update();
            try {
                alerts.deadLettered(jdbc, event, lastError);
                jdbc.sql("RELEASE SAVEPOINT dead_letter_alert").update();
            } catch (RuntimeException alertFailure) {
                jdbc.sql("ROLLBACK TO SAVEPOINT dead_letter_alert").update();
                log.error("Outbox {} {} DEAD_LETTER raised no admin alert", event.eventType(), event.id(),
                        alertFailure);
            }
        }
    }

    /** Class and message only: an exception message never carries an OTP, a secret or a full phone number. */
    private static String describe(RuntimeException failure) {
        String message = failure.getMessage();
        String text = failure.getClass().getName() + (message == null ? "" : ": " + message);
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
