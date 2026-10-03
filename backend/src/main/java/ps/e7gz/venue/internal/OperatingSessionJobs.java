package ps.e7gz.venue.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import ps.e7gz.common.correlation.CorrelationId;

/**
 * The session generator of the worker profile (arch 12 §1.3): every night it extends each venue's rolling horizon by
 * the new day (ADR-009, arch 03 §5). A simple timer until the deadline engine exists, like the photo jobs; regenerating
 * is idempotent per venue and operating day under the venue lock, so two workers running it at once change nothing
 * twice.
 */
@Configuration
@Profile("worker")
@EnableScheduling
class OperatingSessionJobs {

    private static final Logger log = LoggerFactory.getLogger(OperatingSessionJobs.class);

    private final OpeningHoursService hours;

    OperatingSessionJobs(OpeningHoursService hours) {
        this.hours = hours;
    }

    @Scheduled(cron = "${e7gz.operating-sessions.extend-cron:0 15 3 * * *}", zone = "Africa/Cairo")
    void extendHorizon() {
        try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
            log.info("Operating session horizon extended for {} venues", hours.extendAll());
        }
    }
}
