package ps.e7gz.architecture.predicatefixture;

import java.time.Instant;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.inventory.availability.api.AvailabilityPeriod;
import ps.e7gz.inventory.availability.api.AvailabilityQuery;
import ps.e7gz.inventory.availability.api.PredicateResult;
import ps.e7gz.resource.api.ResourceStates;

/** A Booking-like flow done right: it asks AvailabilityQuery, and may still read a Resource's state for itself. */
public class AsksTheCanonicalQuery {

    private final AvailabilityQuery availability;
    private final ResourceStates resources;

    public AsksTheCanonicalQuery(AvailabilityQuery availability, ResourceStates resources) {
        this.availability = availability;
        this.resources = resources;
    }

    PredicateResult revalidate(CommandContext command, UUID resourceId, AvailabilityPeriod period, Instant at) {
        resources.find(command, resourceId);
        return availability.isFree(command, resourceId, period, at);
    }
}
