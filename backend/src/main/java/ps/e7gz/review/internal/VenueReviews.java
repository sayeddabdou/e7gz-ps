package ps.e7gz.review.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.trust.api.TrustCustomers;
import ps.e7gz.venue.api.VenueAccountVenues;

/**
 * The Venue Reviews page (Spec v5 section 49.7, FR-VEN-39, FR-REV-08; arch 09 section 4.1): view only. The summary is
 * {@code review.venue_rating_stats} (ACTIVE Reviews only, FR-REV-04); the list is the ACTIVE Reviews, newest first,
 * with the author's current username, rating, comment and date. No Customer's block changes anything here (PCR-014
 * item 3), and the Venue has no report or moderation path (arch 10 section 5.3a).
 */
@Component
public class VenueReviews {

    static final int DEFAULT_PAGE_SIZE = 20;

    private final JdbcClient jdbc;
    private final VenueAccountVenues accounts;
    private final TrustCustomers customers;

    VenueReviews(DataSource appDataSource, VenueAccountVenues accounts, TrustCustomers customers) {
        this.jdbc = JdbcClient.create(appDataSource);
        this.accounts = accounts;
        this.customers = customers;
    }

    /** {@code GET /venue/reviews} for the signed-in venue account's Venue. */
    public VenuePage list(UUID venueAccountId, String cursor, Integer limit) {
        UUID venueId = accounts.venueOf(Objects.requireNonNull(venueAccountId, "venueAccountId"));
        ReviewCursor after = ReviewCursor.decode(cursor);
        int size = limit == null ? DEFAULT_PAGE_SIZE : limit;
        Summary summary = jdbc.sql("""
                        SELECT average_rating, review_count, distribution_1, distribution_2, distribution_3,
                               distribution_4, distribution_5
                          FROM review.venue_rating_stats WHERE venue_id = :venue""")
                .param("venue", venueId)
                .query((rs, n) -> new Summary(rs.getBigDecimal("average_rating"), rs.getInt("review_count"),
                        rs.getInt("distribution_1"), rs.getInt("distribution_2"), rs.getInt("distribution_3"),
                        rs.getInt("distribution_4"), rs.getInt("distribution_5")))
                .optional().orElse(Summary.NONE);
        List<ReviewRow> rows = ReviewStore.publicPage(jdbc, venueId, null, after, size + 1);
        boolean more = rows.size() > size;
        List<ReviewRow> page = more ? rows.subList(0, size) : rows;
        Map<UUID, TrustCustomers.Customer> authors = customers.of(page.stream().map(ReviewRow::userId).toList());
        List<CustomerReviews.PublicReview> items = page.stream()
                .map(row -> new CustomerReviews.PublicReview(row, CustomerReviews.username(authors, row.userId()), false))
                .toList();
        return new VenuePage(summary, items,
                more ? new ReviewCursor(page.getLast().createdAt(), page.getLast().id()).encode() : null);
    }

    /** {@code averageRating} is null exactly when there is no ACTIVE Review ({@code ck_rating_stats_average}). */
    public record Summary(BigDecimal averageRating, int reviewCount, int one, int two, int three, int four, int five) {

        static final Summary NONE = new Summary(null, 0, 0, 0, 0, 0, 0);
    }

    public record VenuePage(Summary summary, List<CustomerReviews.PublicReview> items, String nextCursor) {
    }
}
