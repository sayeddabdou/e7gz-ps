package ps.e7gz.resource.api;

import java.util.UUID;
import ps.e7gz.audit.api.OwnerPasswordCheck;
import ps.e7gz.common.codes.ResourceBaseState;
import ps.e7gz.common.command.CommandContext;

/**
 * A resource Emergency converted into Disable or Archive (E7-82; FR-OPS-23: the conversion obeys the target action's own
 * authorization and impact rules and never bypasses the resource state-change predicate). The very change the Venue
 * Dashboard's Disable and Archive make (FR-VEN-03, FR-VEN-14, E7-26), on the caller's command transaction, after the
 * caller checked the Owner Password ({@code check}) under the venue and resource locks.
 */
public interface ResourceBaseStateChanges {

    /**
     * Moves the venue's resource to {@code target} - {@code DISABLED} from ACTIVE, or {@code ARCHIVED} from ACTIVE or
     * DISABLED - with its state-change row (owner password used), its audit row recording {@code check} and its room
     * event. Refused, changing nothing, while a current or future blocking reservation remains:
     * {@code 422 RESOURCE_HAS_BLOCKING_RESERVATIONS} with the blocking reservations in the details (FR-VEN-14,
     * FR-OPS-43); a state the action does not fit is {@code VALIDATION_FAILED} on baseState; another venue's resource is
     * NOT_FOUND.
     *
     * @throws IllegalArgumentException for a target other than DISABLED or ARCHIVED
     */
    void change(CommandContext command, UUID venueAccountId, UUID venueId, UUID resourceId, ResourceBaseState target,
                OwnerPasswordCheck check, UUID correlationId);

    /**
     * E7-84 (FR-OPS-33, Spec v5 section 43.8): the Owner Review's resolution clears the restriction -
     * {@code OWNER_REVIEW_REQUIRED -> NONE} with its state-change row (owner password used, actor the venue account) and
     * a {@code ResourceRestrictionChanged} room event, so Nearest Availability refreshes. The caller resolves the open
     * {@code operations.owner_review} row in the same transaction and audits the whole with its Owner Password check;
     * the database checks the final state at commit ({@code tr_owner_review_restriction_clear},
     * {@code tr_owner_review_resolution_final_state}).
     *
     * @throws IllegalStateException when the venue's resource is not under {@code OWNER_REVIEW_REQUIRED} - a caller
     *                               defect
     */
    void clearOwnerReviewRestriction(CommandContext command, UUID venueAccountId, UUID venueId, UUID resourceId,
                                     UUID correlationId);
}
