package ps.e7gz.messaging.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import ps.e7gz.messaging.api.OutboxConsumer;

/**
 * Test support for modules whose effect leaves through the outbox (E7-41): the test profile runs no dispatcher thread,
 * so a test delivers the due events of its consumers' types itself, with a real {@link OutboxDispatcher} and the
 * arch 08 section 4.3 back-off. Events of other types are left alone.
 */
public final class OutboxDrain {

    private OutboxDrain() {
    }

    /** Delivers every due event of the consumers' types, in id order; returns how many it settled. */
    public static int drain(DataSource appDataSource, ObjectMapper json, OutboxConsumer... consumers) {
        OutboxDispatcher dispatcher = new OutboxDispatcher("test-drain", new ConsumerRegistry(List.of(consumers)),
                new JdbcOutboxStore(json), appDataSource, new OutboxProperties(Duration.ofSeconds(1), 1,
                        List.of(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2),
                                Duration.ofMinutes(10), Duration.ofHours(1))));
        int settled = 0;
        while (dispatcher.dispatchNext()) {
            settled++;
        }
        return settled;
    }
}
