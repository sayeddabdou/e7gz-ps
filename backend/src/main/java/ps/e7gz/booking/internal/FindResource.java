package ps.e7gz.booking.internal;

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
 * Read-only: the Resource a customer request names, before the creation command - whose lock set names the Resource,
 * which {@code begin_command} refuses outright when it does not exist. Resources are never deleted (archived only) and
 * never change Venue, activity or console without their own command, so the answer holds when the creation command
 * runs; that command re-reads the Resource's states under its locks (the same rule as {@code Pricing.writeOverrides}).
 * An unknown Resource is {@code 404 NOT_FOUND} for both creation and the quote (PO decision 9 Oct 2026).
 */
record FindResource(ResourceCatalog resources, UUID resourceId) implements Command<ResourceCatalog.Entry> {

    FindResource {
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(resourceId, "resourceId");
    }

    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
    }

    /** Locks nothing: one read, then the command commits. */
    @Override
    public CommandScope scope() {
        return CommandScope.shared(LockSet.empty());
    }

    @Override
    public ResourceCatalog.Entry execute(CommandContext context) {
        return resources.find(context, resourceId).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }
}
