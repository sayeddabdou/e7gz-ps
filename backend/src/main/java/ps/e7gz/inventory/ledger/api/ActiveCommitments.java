package ps.e7gz.inventory.ledger.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: a venue's active commitment rows still to start. Added for the opening-hours impact preview (E7-25,
 * FR-VEN-23 steps 2 and 4): the Venue module finds the Bookings and Tournament holds new hours would no longer cover
 * from these rows, without reading the ledger table itself.
 */
public interface ActiveCommitments {

    /**
     * The active {@code BOOKING}, {@code LATE_CONFIRM_HOLD}, {@code TOURNAMENT_APPROVAL_HOLD} and
     * {@code TOURNAMENT_HOLD} rows on the venue's Resources whose planned start is after {@code after}, in planned-start
     * order. Released rows and {@code MAINTENANCE} / {@code CLOSURE} rows are not included. Reads on the command's own
     * transaction.
     */
    List<Commitment> startingAfter(CommandContext command, UUID venueId, Instant after);

    /**
     * The rows that block Disable, Archive and Console Change of the Resource (FR-VEN-14, Product Spec §13.4, arch 05
     * "the blocking set the Dashboard must display"): every active row except {@code CLOSURE} - a normal closure alone
     * does not block - whose planned interval has not completely ended at {@code at}, in planned-start order. Added for
     * E7-26. Reads on the command's own transaction.
     */
    List<Commitment> blockingResourceChange(CommandContext command, UUID resourceId, Instant at);

    /**
     * One active row: its id, Resource, owner and planned interval {@code [plannedStart, plannedEnd)}. Only
     * {@link #blockingResourceChange} returns {@code MAINTENANCE} owners.
     */
    record Commitment(UUID reservationId, UUID resourceId, ReservationOwner owner, Instant plannedStart,
                      Instant plannedEnd) {
    }
}
