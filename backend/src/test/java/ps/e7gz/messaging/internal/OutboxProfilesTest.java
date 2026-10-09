package ps.e7gz.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.messaging.api.OutboxConsumer;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-41: the {@code api} profile publishes events but never delivers one; the dispatcher and every consumer belong to
 * the {@code worker} profile (arch 12 section 1.3).
 */
@ActiveProfiles("api")
class OutboxProfilesTest extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void theApiProfilePublishesButStartsNoDispatcher() {
        assertThat(context.getBeansOfType(Outbox.class)).hasSize(1);
        assertThat(context.getBeansOfType(OutboxLoop.class)).isEmpty();
        assertThat(context.getBeansOfType(OutboxConsumer.class)).isEmpty();
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(t -> t.getName().startsWith("e7gz-outbox-"));
    }

    @Test
    void theDispatcherAndEveryConsumerBelongToTheWorkerProfile() throws ClassNotFoundException {
        for (String name : new String[] {"ps.e7gz.messaging.internal.OutboxWorkerConfig",
                "ps.e7gz.identity.internal.PhoneOtpSms",
                "ps.e7gz.inventory.availability.internal.NearestAvailabilityEvents"}) {
            Profile profile = Class.forName(name).getAnnotation(Profile.class);
            assertThat(profile).as(name).isNotNull();
            assertThat(profile.value()).as(name).containsExactly("worker");
        }
    }
}
