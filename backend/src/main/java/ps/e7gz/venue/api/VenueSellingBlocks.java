package ps.e7gz.venue.api;

import java.time.Instant;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: the Venue's operational selling block, for check 1 of the availability predicate (arch 05 section 2.2,
 * {@code FR-AVL-07}; PO decision 3 Oct 2026). A selling block is a state, not an interval: an emergency block stays in
 * force past the emergency's declared interval until it is ended.
 */
public interface VenueSellingBlocks {

    /**
     * Whether a selling block of the Venue is in force at {@code at}: started at or before {@code at} and not ended by
     * then. Reads on the command's own transaction.
     */
    boolean venueBlocked(CommandContext command, UUID venueId, Instant at);
}
