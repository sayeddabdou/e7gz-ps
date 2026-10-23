package ps.e7gz.resource.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.codes.OperationalRestriction;
import ps.e7gz.common.codes.ResourceBaseState;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: a Resource's Venue, base state, operational restriction and selling block, for checks 2-4 of the
 * availability predicate (arch 05 section 2.2; PO decision 3 Oct 2026). Every read runs on the command's own
 * transaction.
 */
public interface ResourceStates {

    /** The Resource's Venue and its two independent state domains; empty when the Resource does not exist. */
    Optional<ResourceState> find(CommandContext command, UUID resourceId);

    /**
     * Whether a selling block of the Resource is in force at {@code at}: started at or before {@code at} and not ended
     * by then. A state, not an interval ({@code FR-AVL-07}).
     */
    boolean resourceBlocked(CommandContext command, UUID resourceId, Instant at);

    /**
     * The Resource's base state as it stood at {@code at}, from its append-only state-change chain - for a start-time
     * decision taken late, which must judge the state at {@code startAt} (Spec v5 section 31.4, FR-BOK-28; E7-59
     * delayed-start race, for E7-60). Empty when the Resource does not exist.
     */
    Optional<ResourceBaseState> baseStateAt(CommandContext command, UUID resourceId, Instant at);

    /** Base state and restriction are separate: both must allow selling ({@code FR-AVL-06}, {@code FR-VEN-13}). */
    record ResourceState(UUID resourceId, UUID venueId, ResourceBaseState baseState,
                         OperationalRestriction restriction) {

        public ResourceState {
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(venueId, "venueId");
            Objects.requireNonNull(baseState, "baseState");
            Objects.requireNonNull(restriction, "restriction");
        }
    }
}
