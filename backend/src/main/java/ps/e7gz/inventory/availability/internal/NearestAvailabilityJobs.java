package ps.e7gz.inventory.availability.internal;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import ps.e7gz.scheduling.api.Recurrence;
import ps.e7gz.scheduling.api.RecurringJob;

/**
 * The Nearest Availability refresh of the worker profile (arch 05 section 5.3, arch 08 section 7; PO decision 8 Oct
 * 2026, Sprint 1 review): every pass refreshes every venue / activity pair. Two things move a row:
 * <ul>
 *   <li>inventory-affecting changes - reservations, Resource states, selling blocks, maintenance, closures,
 *       emergencies, opening hours, prices. Refreshing on those events needs the outbox (E7-41); until then this pass
 *       bounds how stale a row can be to one interval;</li>
 *   <li>time itself: {@code now + 15 minutes} (FR-BOK-02) advances, so a stored start drifts into the past even when
 *       nothing changes.</li>
 * </ul>
 * A recurring system job on {@code technical.scheduled_job} (E7-40), one run per interval
 * ({@code e7gz.availability.projection.refresh-every}). Safe on several workers at once: a refresh never overwrites a
 * row refreshed later than itself ({@link JdbcAvailabilityProjectionStore}).
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
class NearestAvailabilityJobs {

    static final String TYPE = "NEAREST_AVAILABILITY_REFRESH";

    private static final Logger log = LoggerFactory.getLogger(NearestAvailabilityJobs.class);

    private final NearestAvailabilityProjectionService projection;

    NearestAvailabilityJobs(NearestAvailabilityProjectionService projection) {
        this.projection = projection;
    }

    @Bean
    RecurringJob nearestAvailabilityRefreshJob(
            @Value("${e7gz.availability.projection.refresh-every:1m}") Duration refreshEvery) {
        return new RecurringJob(TYPE, Recurrence.every(refreshEvery), this::refreshEveryPair);
    }

    void refreshEveryPair() {
        NearestAvailabilityProjectionService.RefreshPass pass = projection.refreshEveryPair();
        if (pass.failed() > 0) {
            log.warn("Nearest Availability pass: {} of {} pairs failed", pass.failed(), pass.pairs());
        } else {
            log.debug("Nearest Availability pass: {} pairs refreshed", pass.pairs());
        }
    }
}
