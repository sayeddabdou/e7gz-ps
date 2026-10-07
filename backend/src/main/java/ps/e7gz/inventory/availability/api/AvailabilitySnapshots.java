package ps.e7gz.inventory.availability.api;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.money.Money;

/**
 * Read-only: the Nearest Availability projection rows of a page of venues, for customer discovery (arch 09 section
 * 3.2, NFR-11; E7-34). One indexed read by primary key. It never refreshes, rebuilds or asks the canonical predicate:
 * the values are the snapshot E7-33 last wrote, advisory only ({@code FR-AVL-11}), and never read on a commit path.
 */
public interface AvailabilitySnapshots {

    /** The rows of the given venues for one activity; a venue without a row is left out. */
    Map<UUID, Snapshot> of(Collection<UUID> venueIds, Activity activity);

    /** Either value may be absent (see {@link NearestAvailabilityProjection.Row}). */
    record Snapshot(UUID venueId, Instant nearestAvailableAt, Money startingPrice) {

        public Snapshot {
            Objects.requireNonNull(venueId, "venueId");
        }
    }
}
