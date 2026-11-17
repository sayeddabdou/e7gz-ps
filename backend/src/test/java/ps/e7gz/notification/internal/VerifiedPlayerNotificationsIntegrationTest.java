package ps.e7gz.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;
import ps.e7gz.trust.internal.VerifiedPlayerFlows;

/**
 * E7-72 - the customer's Verified Player notifications on the real database, through the one notification path: each
 * committed {@code VerifiedPlayerTransitioned} gives its notification once, however often the event is retried. The
 * worker profile is off in tests, so the consumer is built here as the worker builds it.
 */
class VerifiedPlayerNotificationsIntegrationTest extends IntegrationTest {

    @Autowired
    CommandRunner runner;

    @Autowired
    NotificationService notifications;

    @Autowired
    DataSource appDataSource;

    @Autowired
    ObjectMapper json;

    @Autowired
    ApplicationContext context;

    private JdbcClient db;
    private VerifiedPlayerNotificationConsumer consumer;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(appDataSource);
        consumer = new VerifiedPlayerNotificationConsumer(runner, notifications);
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    @Test
    void unlockedThenRevokedByCorrectionEachNotifyOnce() {
        VerifiedPlayerFlows flows = VerifiedPlayerFlows.of(context);
        List<Booking> ten = flows.attendedCompletions(10);
        UUID customer = ten.getFirst().customerId();
        flows.attendance().correct(Fixtures.anAdmin().create().id(), ten.getFirst().bookingId(),
                AttendanceResult.ATTENDANCE_UNCONFIRMED, "Check-in was on the wrong booking.");

        List<OutboxEvent> events = transitioned(customer);
        assertThat(events).hasSize(2);
        for (OutboxEvent event : events) {
            consumer.handle(event);
            consumer.handle(event);         // a retried event: every consumer runs again
        }

        assertThat(db.sql("SELECT code FROM notification.notification WHERE recipient_id = :id ORDER BY created_at")
                .param("id", customer).query(String.class).list())
                .containsExactly("VERIFIED_PLAYER_UNLOCKED", "VERIFIED_PLAYER_REVOKED");
        assertThat(db.sql("""
                        SELECT n.code || ' ' || d.channel
                          FROM notification.notification_delivery d
                          JOIN notification.notification n ON n.id = d.notification_id
                         WHERE n.recipient_id = :id""")
                .param("id", customer).query(String.class).list())
                .contains("VERIFIED_PLAYER_UNLOCKED IN_APP", "VERIFIED_PLAYER_REVOKED IN_APP",
                        "VERIFIED_PLAYER_REVOKED EMAIL");
    }

    private List<OutboxEvent> transitioned(UUID customer) {
        return db.sql("""
                        SELECT id, event_type, aggregate_type, aggregate_id, payload::text AS payload, correlation_id,
                               created_at, attempt
                          FROM technical.outbox_event
                         WHERE event_type = :type AND aggregate_id = :user ORDER BY id""")
                .param("type", IntegrationEvents.VERIFIED_PLAYER_TRANSITIONED).param("user", customer)
                .query((rs, n) -> new OutboxEvent(rs.getLong("id"), rs.getString("event_type"),
                        rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class),
                        tree(rs.getString("payload")), rs.getObject("correlation_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getInt("attempt")))
                .list();
    }

    private JsonNode tree(String text) {
        try {
            return json.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
