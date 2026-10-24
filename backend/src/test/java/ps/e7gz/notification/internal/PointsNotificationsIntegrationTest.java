package ps.e7gz.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.codes.PointsTransactionCause;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-66 (PO decision 23 Oct 2026) - the points notifications on the real database, through the one notification path:
 * the consumer takes the committed {@code LOYALTY_POINTS_LEDGER_COMMITTED} event and creates the notification once,
 * however often the event is retried. The worker profile is off in tests, so the consumer is built here as the worker
 * builds it.
 */
class PointsNotificationsIntegrationTest extends IntegrationTest {

    @Autowired
    CommandRunner runner;

    @Autowired
    NotificationService notifications;

    @Autowired
    DataSource appDataSource;

    @Autowired
    ObjectMapper json;

    private JdbcClient db;
    private PointsNotificationConsumer consumer;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(appDataSource);
        consumer = new PointsNotificationConsumer(runner, notifications);
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    @Test
    void anAdminAdjustmentNotifiesTheCustomerOnceInAppAndByEmail() {
        UUID customer = Fixtures.aUser().verified().create().id();
        UUID admin = Fixtures.anAdmin().create().id();
        adjust(admin, customer, 150);
        OutboxEvent event = theOnlyPointsEvent(customer);

        consumer.handle(event);
        consumer.handle(event);     // a retried event: every consumer runs again

        assertThat(notificationsOf(customer)).containsExactly("ADMIN_ACCOUNT_CHANGE");
        assertThat(deliveriesOf(customer)).containsExactlyInAnyOrder("IN_APP SENT", "EMAIL PENDING");
    }

    @Test
    void anAdminDeductionFromZeroNotifiesNobody() {
        UUID customer = Fixtures.aUser().verified().create().id();
        adjust(Fixtures.anAdmin().create().id(), customer, -50);

        consumer.handle(theOnlyPointsEvent(customer));

        assertThat(notificationsOf(customer)).isEmpty();
    }

    @Test
    void earnedPointsNotifyTheCustomerOnceInAppAndByPushWithThePoints() {
        // An attended completion is E7-61's command; its event is built here as loyalty.post_points writes it
        UUID customer = Fixtures.aUser().verified().create().id();
        OutboxEvent earned = PointsNotificationRulesTest.event(PointsTransactionCause.ATTENDED_COMPLETION, 200);
        ObjectNode payload = ((ObjectNode) earned.payload().deepCopy()).put("recipientUserId", customer.toString());
        OutboxEvent event = new OutboxEvent(System.nanoTime(), earned.eventType(), earned.aggregateType(),
                earned.aggregateId(), payload, earned.correlationId(), earned.occurredAt(), 0);

        consumer.handle(event);
        consumer.handle(event);

        assertThat(notificationsOf(customer)).containsExactly("POINTS_EARNED");
        assertThat(db.sql("SELECT params->>'points' FROM notification.notification WHERE recipient_id = :id")
                .param("id", customer).query(String.class).single()).isEqualTo("200");
        assertThat(deliveriesOf(customer)).containsExactlyInAnyOrder("IN_APP SENT", "PUSH PENDING");
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    /** {@code loyalty.adjust_points} in an admin command, as {@code PointsAdjustments} runs it. */
    private void adjust(UUID admin, UUID customer, int amount) {
        UUID correlationId = CorrelationId.require();
        runner.run(new Command<Void>() {
            @Override
            public EligibilityCategory eligibilityCategory() {
                return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
            }

            @Override
            public CommandScope scope() {
                return CommandScope.admin(admin, correlationId, LockSet.builder().users(customer).build());
            }

            @Override
            public Void execute(CommandContext context) {
                context.jdbc().sql("SELECT loyalty.begin_admin_command(ARRAY[CAST(:user AS uuid)], :admin, :c)")
                        .param("user", customer).param("admin", admin).param("c", correlationId).query().singleRow();
                context.jdbc().sql("SELECT loyalty.adjust_points(:user, :amount, :request, 'E7-66 test')")
                        .param("user", customer).param("amount", amount).param("request", UUID.randomUUID())
                        .query().singleRow();
                return null;
            }
        });
    }

    private OutboxEvent theOnlyPointsEvent(UUID customer) {
        List<OutboxEvent> events = db.sql("""
                        SELECT id, event_type, aggregate_type, aggregate_id, payload::text AS payload, correlation_id,
                               created_at, attempt
                          FROM technical.outbox_event
                         WHERE event_type = :type AND payload->>'recipientUserId' = :user""")
                .param("type", IntegrationEvents.LOYALTY_POINTS_LEDGER_COMMITTED).param("user", customer.toString())
                .query((rs, n) -> new OutboxEvent(rs.getLong("id"), rs.getString("event_type"),
                        rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class),
                        tree(rs.getString("payload")), rs.getObject("correlation_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getInt("attempt")))
                .list();
        assertThat(events).hasSize(1);
        return events.getFirst();
    }

    private List<String> notificationsOf(UUID customer) {
        return db.sql("SELECT code FROM notification.notification WHERE recipient_id = :id")
                .param("id", customer).query(String.class).list();
    }

    private List<String> deliveriesOf(UUID customer) {
        return db.sql("""
                        SELECT d.channel || ' ' || d.status
                          FROM notification.notification_delivery d
                          JOIN notification.notification n ON n.id = d.notification_id
                         WHERE n.recipient_id = :id""")
                .param("id", customer).query(String.class).list();
    }

    private JsonNode tree(String text) {
        try {
            return json.readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
