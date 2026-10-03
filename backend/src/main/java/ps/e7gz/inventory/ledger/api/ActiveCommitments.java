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

    /** One active row: its id, Resource, owner and planned interval {@code [plannedStart, plannedEnd)}. */
    record Commitment(UUID reservationId, UUID resourceId, ReservationOwner owner, Instant plannedStart,
                      Instant plannedEnd) {
    }
}
