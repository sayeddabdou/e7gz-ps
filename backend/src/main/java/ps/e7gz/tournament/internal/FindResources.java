package ps.e7gz.tournament.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.resource.api.ResourceCatalog;

/**
 * The Resources a new Tournament names, each of {@code venueId}, read before the creating command so it can lock them
 * (E7-91). One that does not exist or is of another Venue is {@code NOT_FOUND}: a Venue never learns of another
 * Venue's Resources.
 */
record FindResources(ResourceCatalog resources, UUID venueId, List<UUID> resourceIds)
        implements Command<List<ResourceCatalog.Entry>> {

    FindResources {
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(venueId, "venueId");
        resourceIds = List.copyOf(resourceIds);
    }

    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
    }

    /** Locks nothing: reads, then the command commits. */
    @Override
    public CommandScope scope() {
        return CommandScope.shared(LockSet.empty());
    }

    @Override
    public List<ResourceCatalog.Entry> execute(CommandContext context) {
        List<ResourceCatalog.Entry> entries = new ArrayList<>(resourceIds.size());
        for (UUID resourceId : resourceIds) {
            entries.add(resources.find(context, resourceId)
                    .filter(entry -> entry.venueId().equals(venueId))
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND)));
        }
        return entries;
    }
}
