package ps.e7gz.architecture.predicatefixture;

import java.time.Instant;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.operations.api.OperationalOverlaps;
import ps.e7gz.resource.api.ResourceStates;
import ps.e7gz.venue.api.VenueSellingBlocks;

/** A flow that re-implements part of the eight-check chain from the owner reads instead of asking AvailabilityQuery. */
public class RebuildsTheEightChecks {

    private final VenueSellingBlocks venues;
    private final ResourceStates resources;
    private final OperationalOverlaps operations;
    private final ReservationLedger ledger;

    public RebuildsTheEightChecks(VenueSellingBlocks venues, ResourceStates resources, OperationalOverlaps operations,
                                  ReservationLedger ledger) {
        this.venues = venues;
        this.resources = resources;
        this.operations = operations;
        this.ledger = ledger;
    }

    boolean free(CommandContext command, UUID venueId, UUID resourceId, Instant start, Instant end) {
        return !venues.venueBlocked(command, venueId, start)
                && !resources.resourceBlocked(command, resourceId, start)
                && !operations.maintenanceOverlaps(command, resourceId, start, end)
                && !ledger.hasBlockingOverlap(command, resourceId, start, end);
    }
}
