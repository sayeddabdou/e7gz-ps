package ps.e7gz.review.internal;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.NotificationRecipientType;
import ps.e7gz.common.codes.ReviewStatus;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.notification.api.NotificationRequest;
import ps.e7gz.notification.api.Notifications;
import ps.e7gz.trust.api.TrustCustomers;
import ps.e7gz.venue.api.VenueDiscovery;

/**
 * A Customer's own Review of a Venue and a Venue's public Reviews (Spec v5 sections 49.1-49.6, 49.9; FR-REV-01 ...
 * FR-REV-07, FR-REV-10, FR-REV-12; arch 09 section 3.4; E7-102).
 *
 * <p>Every write is one command under the author's and the Venue's locks ({@code review.customer_command} requires
 * both in the shared scope) that calls the producer: {@code review.create_review}, {@code edit_review} or
 * {@code delete_own_review}. The database decides eligibility (phone-verified, not anonymised), one current Review
 * ({@code REVIEW_ALREADY_EXISTS}), the 30-day cooldown ({@code REVIEW_COOLDOWN_ACTIVE}) and whether a new Review starts
 * ACTIVE or PENDING_MODERATION, writes the audit and refreshes the rating; its refusals map through
 * {@code DatabaseErrorCodes}. A ban does not stop any of this (FR-ACC-17). The edit and the delete act on the
 * author's current Review of the Venue, found under the locks; none is {@code NOT_FOUND}.
 *
 * <p>A Review that starts PENDING_MODERATION notifies its author ({@code REVIEW_SUBMITTED_FOR_MODERATION}, Spec v5
 * section 49.9) in the same transaction.
 */
@Component
public class CustomerReviews {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_COMMENT = 2000;

    private final JdbcClient jdbc;
    private final CommandRunner runner;
    private final VenueDiscovery venues;
    private final TrustCustomers customers;
    private final Notifications notifications;

    CustomerReviews(DataSource appDataSource, CommandRunner runner, VenueDiscovery venues, TrustCustomers customers,
                    Notifications notifications) {
        this.jdbc = JdbcClient.create(appDataSource);
        this.runner = runner;
        this.venues = venues;
        this.customers = customers;
        this.notifications = notifications;
    }

    /** {@code POST /customer/venues/{venueId}/review}. Any existing Venue, whatever its status (FR-REV-09). */
    public ReviewRow create(UUID userId, UUID venueId, int rating, String comment) {
        Objects.requireNonNull(userId, "userId");
        requireVenue(venueId);
        short stars = rating(rating);
        String text = comment(comment);
        UUID correlation = CorrelationId.require();
        return runner.run(new ReviewCommand<>(scope(userId, venueId), context -> {
            UUID id = context.jdbc().sql("""
                            SELECT review.create_review(:user, :venue, CAST(:rating AS smallint), :comment, :correlation)""")
                    .param("user", userId).param("venue", venueId).param("rating", stars).param("comment", text)
                    .param("correlation", correlation)
                    .query(UUID.class).single();
            ReviewRow created = ReviewStore.find(context.jdbc(), id).orElseThrow();
            if (created.status() == ReviewStatus.PENDING_MODERATION) {               // section 49.6 re-entry
                notifications.notify(context, NotificationRequest.of("REVIEW_SUBMITTED_FOR_MODERATION",
                        NotificationRecipientType.CUSTOMER, userId,
                        Map.of("reviewId", id.toString(), "venueId", venueId.toString())));
            }
            return created;
        }));
    }

    /** {@code PATCH .../review}: content only; the status is kept (a HIDDEN Review stays HIDDEN, section 49.4). */
    public ReviewRow edit(UUID userId, UUID venueId, int rating, String comment) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(venueId, "venueId");
        short stars = rating(rating);
        String text = comment(comment);
        UUID correlation = CorrelationId.require();
        return runner.run(new ReviewCommand<>(scope(userId, venueId), context -> {
            ReviewRow current = currentOrNotFound(context.jdbc(), userId, venueId);
            context.jdbc().sql("""
                            SELECT review.edit_review(:review, :user, CAST(:rating AS smallint), :comment, :correlation)""")
                    .param("review", current.id()).param("user", userId).param("rating", stars).param("comment", text)
                    .param("correlation", correlation)
                    .query(UUID.class).single();
            return ReviewStore.find(context.jdbc(), current.id()).orElseThrow();
        }));
    }

    /** {@code DELETE .../review}: REMOVED by its author, with no moderation event and no cooldown (section 49.5). */
    public void delete(UUID userId, UUID venueId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(venueId, "venueId");
        UUID correlation = CorrelationId.require();
        runner.run(new ReviewCommand<>(scope(userId, venueId), context -> {
            ReviewRow current = currentOrNotFound(context.jdbc(), userId, venueId);
            return context.jdbc().sql("SELECT review.delete_own_review(:review, :user, :correlation)")
                    .param("review", current.id()).param("user", userId).param("correlation", correlation)
                    .query(UUID.class).single();
        }));
    }

    /** {@code GET .../review}: the caller's current Review in any status but REMOVED (PENDING shown as pending). */
    public ReviewRow mine(UUID userId, UUID venueId) {
        return currentOrNotFound(jdbc, Objects.requireNonNull(userId, "userId"),
                Objects.requireNonNull(venueId, "venueId"));
    }

    /**
     * {@code GET /customer/venues/{venueId}/reviews}: ACTIVE Reviews, newest first, with the current username only
     * (FR-REV-02), leaving out every Review whose author the reader blocked (PCR-014). The rating is untouched.
     */
    public ReviewPage<PublicReview> list(UUID reader, UUID venueId, String cursor, Integer limit) {
        Objects.requireNonNull(reader, "reader");
        requireVenue(venueId);
        ReviewCursor after = ReviewCursor.decode(cursor);
        int size = limit == null ? DEFAULT_PAGE_SIZE : limit;
        List<ReviewRow> rows = ReviewStore.publicPage(jdbc, venueId, reader, after, size + 1);
        boolean more = rows.size() > size;
        List<ReviewRow> page = more ? rows.subList(0, size) : rows;
        Map<UUID, TrustCustomers.Customer> authors = customers.of(page.stream().map(ReviewRow::userId).toList());
        List<PublicReview> items = page.stream()
                .map(row -> new PublicReview(row, username(authors, row.userId()), row.userId().equals(reader)))
                .toList();
        return new ReviewPage<>(items, more ? new ReviewCursor(page.getLast().createdAt(), page.getLast().id()).encode()
                : null);
    }

    static String username(Map<UUID, TrustCustomers.Customer> named, UUID userId) {
        TrustCustomers.Customer customer = named.get(userId);
        return customer == null ? "" : customer.username();
    }

    private void requireVenue(UUID venueId) {
        Objects.requireNonNull(venueId, "venueId");
        if (venues.profile(venueId, null).isEmpty()) {
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        }
    }

    private static ReviewRow currentOrNotFound(JdbcClient jdbc, UUID userId, UUID venueId) {
        return ReviewStore.current(jdbc, userId, venueId).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }

    private static CommandScope scope(UUID userId, UUID venueId) {
        return CommandScope.shared(LockSet.builder().users(userId).venues(venueId).build());
    }

    private static short rating(int rating) {
        if (rating < 1 || rating > 5) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "rating");
        }
        return (short) rating;
    }

    private static String comment(String comment) {
        String text = comment == null ? "" : comment.strip();
        if (text.isEmpty() || text.length() > MAX_COMMENT) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "comment");
        }
        return text;
    }

    /** One public Review as a Customer sees it: the author's current username, never a name or an id. */
    public record PublicReview(ReviewRow review, String username, boolean mine) {
    }
}
