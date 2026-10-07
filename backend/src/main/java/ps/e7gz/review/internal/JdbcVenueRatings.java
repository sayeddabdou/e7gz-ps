package ps.e7gz.review.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.inventory.availability.api.DiscoveryRatings;

/**
 * {@code review.venue_rating_stats} by primary key, for customer discovery ({@link DiscoveryRatings}, declared by
 * Availability and implemented here so that Availability never depends on Reviews). A missing row means no ACTIVE
 * Review: {@code review.assert_venue_rating} refuses a commit that leaves ACTIVE Reviews without one.
 */
@Component
class JdbcVenueRatings implements DiscoveryRatings {

    private final JdbcClient jdbc;

    JdbcVenueRatings(DataSource appDataSource) {
        this.jdbc = JdbcClient.create(appDataSource);
    }

    @Override
    public Map<UUID, Rating> of(Collection<UUID> venueIds) {
        if (venueIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Rating> stored = new HashMap<>();
        jdbc.sql("""
                        SELECT venue_id, average_rating, review_count FROM review.venue_rating_stats
                         WHERE venue_id = ANY(:venues)""")
                .param("venues", venueIds.stream().distinct().toArray(UUID[]::new))
                .query(rs -> {
                    stored.put(rs.getObject("venue_id", UUID.class),
                            new Rating(rs.getBigDecimal("average_rating"), rs.getInt("review_count")));
                });
        Map<UUID, Rating> ratings = new LinkedHashMap<>();
        venueIds.forEach(venue -> ratings.put(venue, stored.getOrDefault(venue, Rating.NONE)));
        return ratings;
    }
}
