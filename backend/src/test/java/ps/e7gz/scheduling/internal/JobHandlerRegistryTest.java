package ps.e7gz.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.Recurrence;
import ps.e7gz.scheduling.api.RecurringJob;
import ps.e7gz.scheduling.api.BackgroundJobHandler;
import ps.e7gz.scheduling.api.ScheduledJob;

/** E7-40: handlers plug in by type, one per type; the back-off between attempts. */
class JobHandlerRegistryTest {

    @Test
    void handlersAreFoundByTheirTypeAndRecurringJobsAreListed() {
        RecurringJob sweep = new RecurringJob("INVARIANT_SWEEP", Recurrence.nightlyAt(LocalTime.of(4, 0)), () -> { });
        BackgroundJobHandler other = handler("VENUE_RESPONSE_EXPIRY");

        JobHandlerRegistry registry = new JobHandlerRegistry(List.of(sweep, other));

        assertThat(registry.types()).containsExactly("INVARIANT_SWEEP", "VENUE_RESPONSE_EXPIRY");
        assertThat(registry.handler("VENUE_RESPONSE_EXPIRY")).contains(other);
        assertThat(registry.handler("BOOKING_START")).as("a type no handler claims").isEmpty();
        assertThat(registry.recurring()).containsExactly(sweep);
    }

    @Test
    void twoHandlersForOneTypeOrABadTypeAreRefused() {
        assertThatThrownBy(() -> new JobHandlerRegistry(List.of(handler("BOOKING_START"), handler("BOOKING_START"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BOOKING_START");
        assertThatThrownBy(() -> new JobHandlerRegistry(List.of(handler("booking-start"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ck_scheduled_job_type_format");
    }

    @Test
    void theBackOffDoublesAfterEachAttemptUpToItsMaximum() {
        SchedulerProperties properties = new SchedulerProperties(Duration.ofSeconds(1), 2, 10, Duration.ofMinutes(5), 5,
                Duration.ofSeconds(10), Duration.ofSeconds(60), LocalTime.of(4, 0));

        assertThat(properties.backoff(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.backoff(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(properties.backoff(3)).isEqualTo(Duration.ofSeconds(40));
        assertThat(properties.backoff(4)).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.backoff(30)).isEqualTo(Duration.ofSeconds(60));
    }

    private static BackgroundJobHandler handler(String type) {
        return new BackgroundJobHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public JobOutcome run(ScheduledJob job) {
                return JobOutcome.done();
            }
        };
    }
}
