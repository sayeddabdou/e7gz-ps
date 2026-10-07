package ps.e7gz.inventory.availability.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * What customer discovery shows of venues' ratings (FR-DIS-03, FR-DIS-11; E7-34): the statistics of their ACTIVE
 * Reviews ({@code review.venue_rating_stats}, arch 03 section 17.1, FR-REV-04). Declared here and implemented by the
 * Reviews module, so Availability never depends on Reviews (arch 03 section 2.1: Reviews is a higher layer; the same
 * pattern as {@code venue.api.HoursChangeBookings}). Read-only; the statistics are the database's projection, written
 * only by {@code review.refresh_venue_rating}, and nothing here recomputes them.
 */
public interface DiscoveryRatings {

    /** Every given venue, in one read; a venue without a stats row has no ACTIVE Review: {@link Rating#NONE}. */
    Map<UUID, Rating> of(Collection<UUID> venueIds);

    /**
     * {@code averageRating} is null exactly when {@code reviewCount} is 0 ({@code ck_rating_stats_average}): no fake
     * zero average.
     */
    record Rating(BigDecimal averageRating, int reviewCount) {

        public static final Rating NONE = new Rating(null, 0);
    }
}
