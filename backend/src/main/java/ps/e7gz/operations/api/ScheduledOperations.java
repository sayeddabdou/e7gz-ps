package ps.e7gz.operations.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only: the operational intervals the Venue Dashboard schedule shows (FR-VEN-37, E7-112; PO decision 3 Oct
 * 2026): Maintenance windows, normal Venue Closures and the declared intervals of unresolved Emergencies. Operations
 * owns them; this is not an availability answer and is never used to decide one.
 */
public interface ScheduledOperations {

    /**
     * Every interval of the Venue and of {@code resourceIds} overlapping {@code [from, to)}, unmerged, ordered by start:
     * <ul>
     *   <li>{@link Kind#MAINTENANCE}: a {@code maintenance_window} of one of {@code resourceIds};</li>
     *   <li>{@link Kind#VENUE_CLOSURE}: a {@code venue_closure} of the Venue;</li>
     *   <li>{@link Kind#VENUE_EMERGENCY} / {@link Kind#RESOURCE_EMERGENCY}: the declared interval
     *       {@code [declared_at, expected_end_at)} of an unresolved ({@code ACTIVE} / {@code AWAITING_CONFIRMATION})
     *       Emergency, {@code VENUE}-scoped on the Venue or {@code RESOURCE}-scoped on one of {@code resourceIds} of the
     *       Venue. {@code ENDED} and {@code CONVERTED} Emergencies are left out.</li>
     * </ul>
     * Intervals are not clipped. One read outside any command; {@code to} must be after {@code from}.
     */
    List<Interval> overlapping(UUID venueId, Collection<UUID> resourceIds, Instant from, Instant to);

    enum Kind { MAINTENANCE, VENUE_CLOSURE, VENUE_EMERGENCY, RESOURCE_EMERGENCY }

    /** One interval {@code [start, end)}; {@code resourceId} is set for the Resource kinds and null for the Venue kinds. */
    record Interval(Kind kind, UUID resourceId, Instant start, Instant end) {

        public Interval {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            boolean venueWide = kind == Kind.VENUE_CLOSURE || kind == Kind.VENUE_EMERGENCY;
            if (venueWide != (resourceId == null)) {
                throw new IllegalArgumentException(kind + " with resourceId " + resourceId);
            }
        }
    }
}
