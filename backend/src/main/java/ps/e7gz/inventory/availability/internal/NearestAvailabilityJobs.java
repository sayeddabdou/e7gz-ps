package ps.e7gz.inventory.availability.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import ps.e7gz.common.correlation.CorrelationId;

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
 * A simple timer until the deadline engine exists, like the photo jobs; E7-40 moves it onto
 * {@code technical.scheduled_job}. Safe on several workers at once: a refresh never overwrites a row refreshed later
 * than itself ({@link JdbcAvailabilityProjectionStore}).
 */
@Configuration
@Profile("worker")
@EnableScheduling
class NearestAvailabilityJobs {

    private static final Logger log = LoggerFactory.getLogger(NearestAvailabilityJobs.class);

    private final NearestAvailabilityProjectionService projection;

    NearestAvailabilityJobs(NearestAvailabilityProjectionService projection) {
        this.projection = projection;
    }

    @Scheduled(fixedDelayString = "${e7gz.availability.projection.refresh-every:1m}", initialDelayString = "20s")
    void refreshEveryPair() {
        try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
            NearestAvailabilityProjectionService.RefreshPass pass = projection.refreshEveryPair();
            if (pass.failed() > 0) {
                log.warn("Nearest Availability pass: {} of {} pairs failed", pass.failed(), pass.pairs());
            } else {
                log.debug("Nearest Availability pass: {} pairs refreshed", pass.pairs());
            }
        }
    }
}
