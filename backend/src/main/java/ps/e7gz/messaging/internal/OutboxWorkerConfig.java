package ps.e7gz.messaging.internal;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import ps.e7gz.messaging.api.DeadLetterAlerts;
import ps.e7gz.messaging.api.OutboxAttempts;
import ps.e7gz.messaging.api.OutboxConsumer;

/**
 * The outbox dispatcher exists only in the {@code worker} profile (arch 12 section 1.3): the {@code api} profile
 * publishes events but never delivers one.
 *
 * <p>Database connections (arch 12 section 1.4): the dispatcher uses one listening connection and one per thread, held
 * while the event's consumers run, and a consumer's own command takes one more. With the deadline worker's three that is
 * up to six, so {@code application-worker.yml} raises the worker pool from 5 to 8 (E7-41).
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableConfigurationProperties(OutboxProperties.class)
class OutboxWorkerConfig {

    @Bean
    OutboxLoop outboxLoop(List<OutboxConsumer> consumers, JdbcOutboxStore store, DataSource appDataSource,
                          OutboxProperties properties, ObjectProvider<DeadLetterAlerts> deadLetterAlerts) {
        List<DeadLetterAlerts> alerts = deadLetterAlerts.orderedStream().toList();
        ConsumerRegistry registry = new ConsumerRegistry(consumers);
        String process = ManagementFactory.getRuntimeMXBean().getName();      // pid@host
        List<OutboxDispatcher> dispatchers = new ArrayList<>();
        for (int i = 1; i <= properties.threads(); i++) {
            dispatchers.add(new OutboxDispatcher(process + "/" + i, registry, store, appDataSource, properties,
                    alerts));
        }
        return new OutboxLoop(dispatchers, appDataSource, properties.tick());
    }

    /** The attempt about to run is {@code event.attempt() + 1}; the dispatcher dead-letters after the last (E7-43). */
    @Bean
    OutboxAttempts outboxAttempts(OutboxProperties properties) {
        return event -> event.attempt() + 1 >= properties.maxAttempts();
    }
}
