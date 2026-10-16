package ps.e7gz.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.DeadLetterAlerts;
import ps.e7gz.messaging.api.NewOutboxEvent;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.messaging.api.OutboxConsumer;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.messaging.api.PermanentDeliveryFailure;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-56 (PO decision 12 Oct 2026; E7-41 "the admin alert is E7-56"): an outbox event reaching {@code DEAD_LETTER}
 * raises exactly one SEV_2 OUTBOX_DEAD_LETTER alert, in the dispatcher's own transaction, with the event id, type,
 * aggregate, last error and the event's correlation ID. A failing alert never keeps the event from DEAD_LETTER.
 */
class OutboxDeadLetterAlertTest extends IntegrationTest {

    /** Six attempts, each due at once, so a test can use them all up. */
    private static final OutboxProperties IMMEDIATE = new OutboxProperties(Duration.ofSeconds(1), 1,
            Collections.nCopies(5, Duration.ofMillis(1)));

    @Autowired
    CommandRunner runner;

    @Autowired
    Outbox outbox;

    @Autowired
    JdbcOutboxStore store;

    @Autowired
    DataSource appDataSource;

    @Autowired
    ObjectMapper json;

    /** The Platform Admin side the application wires (E7-56). */
    @Autowired
    List<DeadLetterAlerts> deadLetterAlerts;

    @Test
    void aDeadLetteredEventRaisesOneSev2AlertWithItsFacts() throws Exception {
        String type = newType();
        UUID aggregate = UUID.randomUUID();
        UUID correlation = CorrelationId.generate();
        long id = publish(type, aggregate, correlation);

        drain(deadLetterAlerts, consumer(type, event -> {
            throw new PermanentDeliveryFailure("malformed recipient");
        }));
        drain(deadLetterAlerts, consumer(type, event -> { }));             // DEAD_LETTER is final: nothing more

        List<Map<String, Object>> alerts = db().sql("""
                        SELECT kind, severity, subject_type, subject_id, payload::text AS payload, correlation_id
                          FROM admin.admin_alert WHERE subject_id = :aggregate""")
                .param("aggregate", aggregate).query().listOfRows();
        assertThat(alerts).hasSize(1);
        Map<String, Object> alert = alerts.getFirst();
        assertThat(alert.get("kind")).isEqualTo("OUTBOX_DEAD_LETTER");
        assertThat(alert.get("severity")).isEqualTo("SEV_2");
        assertThat(alert.get("subject_type")).isEqualTo("TEST");
        assertThat(alert.get("correlation_id")).isEqualTo(correlation);
        JsonNode payload = json.readTree((String) alert.get("payload"));
        assertThat(payload.path("eventId").asLong()).isEqualTo(id);
        assertThat(payload.path("eventType").asText()).isEqualTo(type);
        assertThat(payload.path("aggregateType").asText()).isEqualTo("TEST");
        assertThat(payload.path("aggregateId").asText()).isEqualTo(aggregate.toString());
        assertThat(payload.path("lastError").asText()).contains("malformed recipient");
        assertThat(payload.path("correlationId").asText()).isEqualTo(correlation.toString());
    }

    @Test
    void aRetriedFailureRaisesNothingUntilItIsDeadLettered() {
        String type = newType();
        UUID aggregate = UUID.randomUUID();
        publish(type, aggregate, CorrelationId.generate());
        OutboxDispatcher dispatcher = dispatcher(deadLetterAlerts, consumer(type, event -> {
            throw new IllegalStateException("provider down");
        }));

        dispatcher.dispatchNext();                                           // attempt 1 of 6: back to PENDING

        assertThat(alertCount(aggregate)).isZero();
    }

    /** The alert sits behind a savepoint: when it fails, the event still reaches DEAD_LETTER and is never redelivered. */
    @Test
    void aFailingAlertDoesNotKeepTheEventFromDeadLetter() {
        String type = newType();
        UUID aggregate = UUID.randomUUID();
        long id = publish(type, aggregate, CorrelationId.generate());
        DeadLetterAlerts failing = (jdbc, event, lastError) ->
                jdbc.sql("SELECT admin.raise_alert('X', 'SEV_2', '', :id, NULL, gen_random_uuid())")
                        .param("id", event.aggregateId()).query().singleRow();   // ck_admin_alert_values refuses it

        drain(List.of(failing), consumer(type, event -> {
            throw new PermanentDeliveryFailure("malformed recipient");
        }));

        assertThat(db().sql("SELECT state FROM technical.outbox_event WHERE id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("DEAD_LETTER");
        assertThat(alertCount(aggregate)).isZero();
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private JdbcClient db() {
        return JdbcClient.create(appDataSource);
    }

    private long alertCount(UUID aggregate) {
        return db().sql("SELECT count(*) FROM admin.admin_alert WHERE subject_id = :aggregate")
                .param("aggregate", aggregate).query(Long.class).single();
    }

    private OutboxDispatcher dispatcher(List<DeadLetterAlerts> alerts, OutboxConsumer consumer) {
        return new OutboxDispatcher("test", new ConsumerRegistry(List.of(consumer)), store, appDataSource, IMMEDIATE,
                alerts);
    }

    private void drain(List<DeadLetterAlerts> alerts, OutboxConsumer consumer) {
        OutboxDispatcher dispatcher = dispatcher(alerts, consumer);
        while (dispatcher.dispatchNext()) {
            // until nothing of this type is due
        }
    }

    private long publish(String type, UUID aggregate, UUID correlation) {
        try (CorrelationId.Scope ignored = CorrelationId.open(correlation)) {
            return runner.run(new Command<Long>() {
                @Override
                public EligibilityCategory eligibilityCategory() {
                    return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
                }

                @Override
                public CommandScope scope() {
                    return CommandScope.shared(LockSet.empty());
                }

                @Override
                public Long execute(CommandContext context) {
                    return outbox.publish(context,
                            new NewOutboxEvent(type, "TEST", aggregate, Map.of("aggregateId", aggregate)));
                }
            });
        }
    }

    private static OutboxConsumer consumer(String type, Consumer<OutboxEvent> body) {
        return new OutboxConsumer() {
            @Override
            public Set<String> eventTypes() {
                return Set.of(type);
            }

            @Override
            public void handle(OutboxEvent event) {
                body.accept(event);
            }
        };
    }

    private static String newType() {
        return "T" + UUID.randomUUID().toString().replace("-", "");
    }
}
