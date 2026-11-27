package ps.e7gz.review.internal;

import java.time.Instant;
import java.util.UUID;
import ps.e7gz.common.codes.ReviewStatus;

/**
 * One {@code review.review} row as read (never written: the review.* producers own every write, arch 04 section 4.7).
 *
 * @param authorDeleted removed by its author ({@code author_deleted_at}, Spec v5 section 49.5), not by moderation
 */
public record ReviewRow(UUID id, UUID userId, UUID venueId, int rating, String comment, ReviewStatus status,
                        boolean authorDeleted, Instant createdAt, Instant updatedAt) {
}
