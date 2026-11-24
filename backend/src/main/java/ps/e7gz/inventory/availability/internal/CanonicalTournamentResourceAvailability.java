package ps.e7gz.inventory.availability.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.inventory.availability.api.AvailabilityCheck;
import ps.e7gz.inventory.availability.api.AvailabilityPeriod;
import ps.e7gz.inventory.availability.api.AvailabilityQuery;
import ps.e7gz.tournament.api.TournamentResourceAvailability;

/**
 * The tournament module's port (E7-91) answered by the one canonical predicate, {@link AvailabilityQuery#isFree}
 * (arch 01 section 15 rule 1): nothing is decided here but how its failing check reads for a new Tournament.
 */
@Component
class CanonicalTournamentResourceAvailability implements TournamentResourceAvailability {

    private final AvailabilityQuery availability;

    CanonicalTournamentResourceAvailability(AvailabilityQuery availability) {
        this.availability = availability;
    }

    @Override
    public Optional<Blocker> blocker(CommandContext command, UUID resourceId, Instant startAt, Instant endAt,
                                     Instant at) {
        return availability.isFree(command, resourceId, new AvailabilityPeriod(startAt, endAt), at).failingCheck()
                .map(CanonicalTournamentResourceAvailability::blockerOf);
    }

    static Blocker blockerOf(AvailabilityCheck check) {
        return switch (check) {
            case VENUE_SELLING_BLOCK -> Blocker.VENUE_SELLING_BLOCK;
            case RESOURCE_SELLING_BLOCK -> Blocker.RESOURCE_SELLING_BLOCK;
            case RESOURCE_BASE_STATE, RESOURCE_OWNER_REVIEW -> Blocker.NOT_ELIGIBLE;
            case EMERGENCY_INTERVAL, MAINTENANCE, CLOSURE, BLOCKING_RESERVATION -> Blocker.ALREADY_RESERVED;
        };
    }
}
