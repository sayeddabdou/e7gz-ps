package ps.e7gz.inventory.availability.internal;

import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ps.e7gz.inventory.availability.api.NearestAvailabilityProjection;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxConsumer;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.venue.api.VenueActivities;

/**
 * The event-driven Nearest Availability refresh (arch 05 section 5.3, arch 08 section 7; Sprint 1 review, PO decisions
 * 8 and 9 Oct 2026, E7-41): every inventory-affecting event refreshes, each in its own command, every venue / activity
 * pair of the venue it names - for {@code AreaDeactivated}, of every venue of the area. A refresh recomputes the row from
 * the authoritative state, so running it twice for one event changes nothing more.
 *
 * <p>Not every producer writes its event yet (PO decision 9 Oct 2026): the one-minute pass
 * ({@link NearestAvailabilityJobs}) stays as the safety net, and it also keeps rows up with time passing.
 */
@Component
@Profile("worker")
class NearestAvailabilityEvents implements OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(NearestAvailabilityEvents.class);

    private final NearestAvailabilityProjection projection;
    private final VenueActivities venueActivities;

    NearestAvailabilityEvents(NearestAvailabilityProjection projection, VenueActivities venueActivities) {
        this.projection = projection;
        this.venueActivities = venueActivities;
    }

    @Override
    public Set<String> eventTypes() {
        return IntegrationEvents.INVENTORY_AFFECTING;
    }

    /** Refreshes every pair; when one fails the others are still refreshed, then the event is retried. */
    @Override
    public void handle(OutboxEvent event) {
        List<VenueActivities.Offering> pairs = IntegrationEvents.AREA_DEACTIVATED.equals(event.eventType())
                ? venueActivities.inArea(event.uuid("areaId"))
                : venueActivities.ofVenue(event.uuid("venueId"));
        RuntimeException failure = null;
        for (VenueActivities.Offering pair : pairs) {
            try {
                projection.refresh(pair.venueId(), pair.activity());
            } catch (RuntimeException e) {
                log.warn("Nearest Availability refresh failed for venue {} / {} on {} {}", pair.venueId(),
                        pair.activity(), event.eventType(), event.id(), e);
                if (failure == null) {
                    failure = e;
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
