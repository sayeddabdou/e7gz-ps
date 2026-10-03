package ps.e7gz.architecture.predicatefixture;

import java.time.Instant;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.inventory.availability.api.AvailabilityCheck;
import ps.e7gz.inventory.availability.api.AvailabilityPeriod;
import ps.e7gz.inventory.availability.api.AvailabilityQuery;
import ps.e7gz.inventory.availability.api.PredicateResult;

/** A second implementation of the predicate, deciding a refusal of its own. */
public class AnotherAvailabilityQuery implements AvailabilityQuery {

    @Override
    public PredicateResult isFree(CommandContext command, UUID resourceId, AvailabilityPeriod period, Instant at) {
        return PredicateResult.notFree(AvailabilityCheck.BLOCKING_RESERVATION);
    }
}
