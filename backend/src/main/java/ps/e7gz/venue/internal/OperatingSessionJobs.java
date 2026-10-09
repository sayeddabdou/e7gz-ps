package ps.e7gz.venue.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import ps.e7gz.scheduling.api.Recurrence;
import ps.e7gz.scheduling.api.RecurringJob;

/**
 * The session generator of the worker profile (arch 12 §1.3): every night it extends each venue's rolling horizon by
 * the new day (ADR-009, arch 03 §5). A nightly system job on {@code technical.scheduled_job} (E7-40) at
 * {@code e7gz.operating-sessions.extend-at}, Africa/Cairo; regenerating is idempotent per venue and operating day under
 * the venue lock, so a run repeated after a crash changes nothing twice.
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
class OperatingSessionJobs {

    static final String TYPE = "OPERATING_SESSION_HORIZON";

    private static final Logger log = LoggerFactory.getLogger(OperatingSessionJobs.class);

    private final OpeningHoursService hours;

    OperatingSessionJobs(OpeningHoursService hours) {
        this.hours = hours;
    }

    @Bean
    RecurringJob operatingSessionHorizonJob(OperatingSessionProperties properties) {
        return new RecurringJob(TYPE, Recurrence.nightlyAt(properties.extendAt()), this::extendHorizon);
    }

    void extendHorizon() {
        log.info("Operating session horizon extended for {} venues", hours.extendAll());
    }
}
