package ps.e7gz.inventory.availability.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.money.Money;

/**
 * The Nearest Availability projection {@code inventory.venue_activity_availability} (arch 05 section 5.3, arch 08
 * section 7, E7-33): a rebuildable read model for discovery, never a source of truth and never read on a commit path
 * ({@code FR-AVL-11}). Each row is recomputed from the authoritative state by the one calculation of arch 05 section
 * 5.2, which asks the canonical {@link AvailabilityQuery} about every candidate.
 *
 * <p>A row exists only for a venue / activity pair with at least one Resource, whatever its state (PO decision 7 Oct
 * 2026): the Resource already keeps the activity in use, so the projection never is the only thing that stops the
 * activity being removed ({@code fk_venue_activity_availability_venue_activity}).
 *
 * <p>The worker profile refreshes every pair every minute ({@code e7gz.availability.projection.refresh-every}; PO
 * decision 8 Oct 2026), which also moves rows forward as time passes. Once the outbox exists (E7-41), the
 * inventory-affecting events of arch 05 section 5.3 also call {@link #refresh} for the pairs they touch.
 */
public interface NearestAvailabilityProjection {

    /**
     * Recomputes the pair's row in its own command and returns it; empty when the pair has no Resource, in which case
     * any row it had is removed. A refresh that started before the row's last refresh changes nothing.
     */
    Optional<Row> refresh(UUID venueId, Activity activity);

    /**
     * Recomputes every venue / activity pair, each in its own command, and removes the rows of pairs without a
     * Resource.
     *
     * <p>Safe to rerun: every run leaves one row per venue / activity pair with a Resource and none for a pair without,
     * accumulates nothing, and each row equals a fresh calculation at its own command instant. With unchanged
     * authoritative state, {@code startingPrice} stays the same; {@code nearestAvailableAt} may still advance, because
     * {@code now + 15 minutes} advances with the database clock.
     */
    RebuildReport rebuild();

    /**
     * One projected row. {@code nearestAvailableAt} and {@code startingPrice} are independent and each may be absent:
     * nothing sellable in the materialized sessions, or no resolvable price.
     *
     * @param startingPrice the hourly "from" rate (PO decision 7 Oct 2026), not the price of a slot
     */
    record Row(UUID venueId, Activity activity, Instant nearestAvailableAt, Money startingPrice, Instant refreshedAt) {

        public Row {
            Objects.requireNonNull(venueId, "venueId");
            Objects.requireNonNull(activity, "activity");
            Objects.requireNonNull(refreshedAt, "refreshedAt");
        }
    }

    /** What a rebuild did: pairs looked at, rows written, rows removed (or not created) for want of a Resource. */
    record RebuildReport(int pairs, int written, int withoutResources) {
    }
}
