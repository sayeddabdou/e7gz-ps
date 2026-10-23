package ps.e7gz.notification.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.messaging.internal.OutboxDrain;
import ps.e7gz.notification.api.NotifiedBookings;
import ps.e7gz.scheduling.api.ScheduledJobs;

/**
 * Test support for other modules (E7-61 journeys): E7-55's one booking-notification consumer, built as the
 * {@code worker} profile builds it (off in tests), run by the real dispatcher over the real outbox
 * ({@link OutboxDrain}), and a read of the notifications a booking has. It adds no behaviour; it only reaches the
 * package-private consumer this package's own tests build, as {@code BookingNotificationsIntegrationTest} does.
 */
public final class NotificationFlows {

    private final DataSource appDataSource;
    private final ObjectMapper json;
    private final BookingNotificationConsumer consumer;

    private NotificationFlows(ApplicationContext context) {
        this.appDataSource = context.getBean("appDataSource", DataSource.class);
        this.json = context.getBean(ObjectMapper.class);
        this.consumer = new BookingNotificationConsumer(context.getBean(CommandRunner.class),
                context.getBean(BookingNotifier.class), context.getBean(NotifiedBookings.class),
                context.getBean(ScheduledJobs.class));
    }

    public static NotificationFlows of(ApplicationContext context) {
        return new NotificationFlows(context);
    }

    /** Dispatches every pending outbox event to the consumer, as the worker would; returns how many it settled. */
    public int drain() {
        return OutboxDrain.drain(appDataSource, json, consumer);
    }

    /** The booking's notifications, each "RECIPIENT_TYPE CODE recipientId". */
    public List<String> notifications(UUID bookingId) {
        return JdbcClient.create(appDataSource).sql("""
                        SELECT recipient_type || ' ' || code || ' ' || recipient_id FROM notification.notification
                         WHERE booking_id = :id""")
                .param("id", bookingId).query(String.class).list();
    }
}
