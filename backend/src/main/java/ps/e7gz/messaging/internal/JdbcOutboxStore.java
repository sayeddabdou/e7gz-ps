package ps.e7gz.messaging.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.messaging.api.NewOutboxEvent;
import ps.e7gz.messaging.api.OutboxEvent;

/**
 * SQL over {@code technical.outbox_event}. A new row is {@code PENDING}, attempt 0 ({@code technical.guard_outbox});
 * its envelope never changes, and its delivery state changes only through {@code technical.outbox_mark_dispatched} /
 * {@code outbox_mark_retry} / {@code outbox_mark_dead_letter}, never a plain {@code UPDATE} (the guard refuses one).
 * Every method runs on the {@link JdbcClient} it is given: the caller's command or the dispatcher's transaction.
 */
@Component
class JdbcOutboxStore {

    /** The {@code LISTEN} / {@code pg_notify} channel (arch 08 section 4.1). */
    static final String CHANNEL = "outbox";

    private final ObjectMapper json;

    JdbcOutboxStore(ObjectMapper json) {
        this.json = json;
    }

    /** Inserts the event {@code PENDING} and returns its id ({@code technical.assign_outbox_id}). */
    long insert(JdbcClient jdbc, NewOutboxEvent event, UUID correlationId, Instant occurredAt) {
        return jdbc.sql("""
                        INSERT INTO technical.outbox_event (event_type, aggregate_type, aggregate_id, payload,
                                                            correlation_id, state, attempt, created_at)
                        VALUES (:type, :aggregateType, :aggregateId, CAST(:payload AS jsonb),
                                :correlation, 'PENDING', 0, :createdAt)
                        RETURNING id""")
                .param("type", event.eventType()).param("aggregateType", event.aggregateType())
                .param("aggregateId", event.aggregateId()).param("payload", write(event))
                .param("correlation", correlationId).param("createdAt", utc(occurredAt))
                .query(Long.class).single();
    }

    /** Wakes every listening dispatcher when the transaction commits; nothing is sent if it rolls back. */
    void wakeDispatchers(JdbcClient jdbc) {
        jdbc.sql("SELECT pg_notify(:channel, '')").param("channel", CHANNEL).query().singleRow();
    }

    /**
     * The oldest due {@code PENDING} event of the given types, locked until the transaction ends ({@code FOR UPDATE SKIP
     * LOCKED}): every other dispatcher skips it meanwhile (arch 08 section 4.1). Due means {@code next_attempt_at} has
     * come, by the database clock (arch 08 section 8): {@code now()}, the start of this short transaction, which unlike
     * {@code clock_timestamp()} lets the poll use {@code ix_outbox_worker (state, next_attempt_at, id)}.
     */
    Optional<OutboxEvent> lockNextDue(JdbcClient jdbc, Collection<String> types) {
        return jdbc.sql("""
                        SELECT id, event_type, aggregate_type, aggregate_id, payload::text AS payload, correlation_id,
                               created_at, attempt
                          FROM technical.outbox_event
                         WHERE state = 'PENDING' AND next_attempt_at <= now()
                           AND event_type = ANY(:types)
                         ORDER BY id
                         LIMIT 1
                           FOR UPDATE SKIP LOCKED""")
                .param("types", types.toArray(String[]::new))
                .query(this::event).optional();
    }

    void markDispatched(JdbcClient jdbc, long id) {
        jdbc.sql("SELECT technical.outbox_mark_dispatched(:id)").param("id", id).query().singleRow();
    }

    /** Back to {@code PENDING} at the database clock plus {@code delay}, with the attempt counted and the error kept. */
    void markRetry(JdbcClient jdbc, long id, Duration delay, String error) {
        jdbc.sql("""
                        SELECT technical.outbox_mark_retry(:id, clock_timestamp() + :millis * interval '1 millisecond',
                                                           :error)""")
                .param("id", id).param("millis", Math.max(1, delay.toMillis())).param("error", error)
                .query().singleRow();
    }

    void markDeadLetter(JdbcClient jdbc, long id, String error) {
        jdbc.sql("SELECT technical.outbox_mark_dead_letter(:id, :error)").param("id", id).param("error", error)
                .query().singleRow();
    }

    private String write(NewOutboxEvent event) {
        try {
            return json.writeValueAsString(event.payload());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(event.eventType() + " payload is not JSON", e);
        }
    }

    private OutboxEvent event(ResultSet rs, int row) throws SQLException {
        try {
            return new OutboxEvent(rs.getLong("id"), rs.getString("event_type"), rs.getString("aggregate_type"),
                    rs.getObject("aggregate_id", UUID.class), json.readTree(rs.getString("payload")),
                    rs.getObject("correlation_id", UUID.class),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getInt("attempt"));
        } catch (JsonProcessingException e) {
            throw new SQLException("outbox_event " + rs.getLong("id") + " payload is not JSON", e);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
