package ps.e7gz.inventory.ledger.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: the merged occupied intervals of Resources, for the free-gap boundaries of Nearest Availability (arch 05
 * section 5.2, E7-33). Not an availability answer: a gap found here is only a candidate, which the caller must still
 * ask {@code AvailabilityQuery.isFree} about.
 */
public interface OccupiedRanges {

    /**
     * The planned intervals of every active row of the Resources - Bookings, holds, and the {@code MAINTENANCE} and
     * {@code CLOSURE} guard rows (arch 05 section 3.1: a new Booking is refused over any of them) - that overlap
     * {@code [from, to)}, merged per Resource so overlapping and back-to-back rows become one range. Released rows never
     * count (EC-AVAI-004). Ordered by Resource, then start; ranges are not clipped to the window. Reads on the command's
     * own transaction; {@code to} must be after {@code from}.
     */
    List<Occupied> occupied(CommandContext command, Collection<UUID> resourceIds, Instant from, Instant to);

    /** One merged occupied interval {@code [start, end)} of a Resource. */
    record Occupied(UUID resourceId, Instant start, Instant end) {

        public Occupied {
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
        }
    }
}
