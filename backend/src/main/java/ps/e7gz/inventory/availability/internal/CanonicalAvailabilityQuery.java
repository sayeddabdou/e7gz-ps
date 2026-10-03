package ps.e7gz.inventory.availability.internal;

import static ps.e7gz.inventory.availability.api.AvailabilityCheck.BLOCKING_RESERVATION;
import static ps.e7gz.inventory.availability.api.AvailabilityCheck.CLOSURE;
import static ps.e7gz.inventory.availability.api.AvailabilityCheck.EMERGENCY_INTERVAL;
import static ps.e7gz.inventory.availability.api.AvailabilityCheck.MAINTENANCE;
import static ps.e7gz.inventory.availability.api.AvailabilityCheck.RESOURCE_BASE_STATE;
import static ps.e7gz.inventory.availability.api.AvailabilityCheck.RESOURCE_OWNER_REVIEW;
import static ps.e7gz.inventory.availability.api.AvailabilityCheck.RESOURCE_SELLING_BLOCK;
import static ps.e7gz.inventory.availability.api.AvailabilityCheck.VENUE_SELLING_BLOCK;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.OperationalRestriction;
import ps.e7gz.common.codes.ResourceBaseState;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.inventory.availability.api.AvailabilityPeriod;
import ps.e7gz.inventory.availability.api.AvailabilityQuery;
import ps.e7gz.inventory.availability.api.PredicateResult;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.operations.api.OperationalOverlaps;
import ps.e7gz.resource.api.ResourceStates;
import ps.e7gz.resource.api.ResourceStates.ResourceState;
import ps.e7gz.venue.api.VenueSellingBlocks;

/**
 * The only implementation of {@code FR-AVL-02}'s eight ordered checks (arch 01 section 15 rule 1, arch 05 section 2.2).
 * Each check reads its owner module through that module's api (PO decision 3 Oct 2026), on the caller's command
 * transaction; this class holds no SQL. The order is frozen and the first failing check is returned at once.
 */
@Component
class CanonicalAvailabilityQuery implements AvailabilityQuery {

    private final VenueSellingBlocks venues;
    private final ResourceStates resources;
    private final OperationalOverlaps operations;
    private final ReservationLedger ledger;

    CanonicalAvailabilityQuery(VenueSellingBlocks venues, ResourceStates resources, OperationalOverlaps operations,
                               ReservationLedger ledger) {
        this.venues = venues;
        this.resources = resources;
        this.operations = operations;
        this.ledger = ledger;
    }

    @Override
    public PredicateResult isFree(CommandContext command, UUID resourceId, AvailabilityPeriod period, Instant at) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(at, "at");

        // The Resource row gives venueOf(resourceId) for checks 1, 5 and 7, and the two state domains of checks 3-4.
        ResourceState resource = resources.find(command, resourceId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        UUID venueId = resource.venueId();

        // 1 - a state lookup, not an interval overlap (FR-AVL-02 #1, FR-AVL-07, EC-AVAI-003)
        if (venues.venueBlocked(command, venueId, at)) {
            return PredicateResult.notFree(VENUE_SELLING_BLOCK);
        }
        // 2 - a state lookup, not an interval overlap (FR-AVL-02 #2, FR-AVL-07)
        if (resources.resourceBlocked(command, resourceId, at)) {
            return PredicateResult.notFree(RESOURCE_SELLING_BLOCK);
        }
        // 3 + 4 - both required, independently (FR-AVL-06, FR-VEN-13, EC-AVAI-002)
        if (resource.baseState() != ResourceBaseState.ACTIVE) {
            return PredicateResult.notFree(RESOURCE_BASE_STATE);
        }
        if (resource.restriction() != OperationalRestriction.NONE) {
            return PredicateResult.notFree(RESOURCE_OWNER_REVIEW);
        }
        // 5 - the declared emergency interval only, never an infinite one (FR-AVL-02, FR-OPS-08)
        if (operations.declaredEmergencyOverlaps(command, venueId, resourceId, period.start(), period.end())) {
            return PredicateResult.notFree(EMERGENCY_INTERVAL);
        }
        // 6
        if (operations.maintenanceOverlaps(command, resourceId, period.start(), period.end())) {
            return PredicateResult.notFree(MAINTENANCE);
        }
        // 7
        if (operations.closureOverlaps(command, venueId, period.start(), period.end())) {
            return PredicateResult.notFree(CLOSURE);
        }
        // 8 - active commitment rows only; MAINTENANCE and CLOSURE rows are checks 6-7 (FR-AVL-03)
        if (ledger.hasBlockingOverlap(command, resourceId, period.start(), period.end())) {
            return PredicateResult.notFree(BLOCKING_RESERVATION);
        }
        return PredicateResult.free();
    }
}
