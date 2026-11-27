package ps.e7gz.review.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.ReviewStatus;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.trust.api.TrustCustomers;

/**
 * A Customer blocks the author of a Review, lists and unblocks (PCR-014, FR-REV-12; v1.1.47
 * {@code review.review_author_block}). A block hides the author's Reviews from the blocker only
 * ({@link ReviewStore#NOT_BLOCKED_BY_READER}); it changes no Review, rating, moderation or other reader, raises no
 * notification and no audit, and the author is never told (PCR-014 items 3, 4, 7).
 *
 * <p>Block and unblock are commands under the Users' locks; the guard dates each write with the command instant.
 * Both are idempotent: blocking a User already blocked answers the block in force, unblocking a User not blocked does
 * nothing. A block starts from a Review the blocker can see (ACTIVE); a Customer cannot block themself
 * ({@code VALIDATION_FAILED}). An anonymised User is never blocked and leaves the list (PCR-014 item 8).
 */
@Component
public class ReviewBlocks {

    static final int DEFAULT_PAGE_SIZE = 20;

    private static final String OPEN = """
            SELECT id, blocked_user_id, blocked_at FROM review.review_author_block
             WHERE customer_user_id = :customer AND blocked_user_id = :blocked AND unblocked_at IS NULL""";

    private final JdbcClient jdbc;
    private final CommandRunner runner;
    private final TrustCustomers customers;

    ReviewBlocks(DataSource appDataSource, CommandRunner runner, TrustCustomers customers) {
        this.jdbc = JdbcClient.create(appDataSource);
        this.runner = runner;
        this.customers = customers;
    }

    /** {@code POST /customer/reviews/{reviewId}/block-author}. */
    public BlockedUser blockAuthor(UUID customerId, UUID reviewId) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(reviewId, "reviewId");
        ReviewRow review = ReviewStore.find(jdbc, reviewId).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        UUID author = review.userId();                                            // immutable (guard_review)
        if (author.equals(customerId)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "reviewId");
        }
        TrustCustomers.Customer named = customers.of(List.of(author)).get(author);
        CommandScope scope = CommandScope.shared(LockSet.builder().users(customerId, author).build());
        Block block = runner.run(new ReviewCommand<>(scope, context -> {
            Optional<Block> open = open(context.jdbc(), customerId, author);
            if (open.isPresent()) {
                return open.get();                                                // already blocked: idempotent
            }
            ReviewRow now = ReviewStore.find(context.jdbc(), reviewId).orElseThrow();
            if (now.status() != ReviewStatus.ACTIVE || named == null || named.deleted()) {
                throw new ApiException(ApiErrorCode.NOT_FOUND);                   // not a Review the blocker sees
            }
            context.jdbc().sql("""
                            INSERT INTO review.review_author_block (id, customer_user_id, blocked_user_id, blocked_at)
                            VALUES (:id, :customer, :blocked, tournament.command_now())""")
                    .param("id", UUID.randomUUID()).param("customer", customerId).param("blocked", author)
                    .update();
            return open(context.jdbc(), customerId, author).orElseThrow();
        }));
        return new BlockedUser(block.id(), named == null ? "" : named.username(), block.blockedAt());
    }

    /**
     * {@code POST /customer/blocked-users/{blockId}/unblock}: that block of the Customer, if still in force, ends now.
     * Another Customer's block, a lifted one or an unknown id changes nothing (and tells nothing).
     */
    public void unblock(UUID customerId, UUID blockId) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(blockId, "blockId");
        CommandScope scope = CommandScope.shared(LockSet.builder().users(customerId).build());
        runner.run(new ReviewCommand<>(scope, context -> context.jdbc().sql("""
                        UPDATE review.review_author_block SET unblocked_at = tournament.command_now()
                         WHERE id = :block AND customer_user_id = :customer AND unblocked_at IS NULL""")
                .param("block", blockId).param("customer", customerId)
                .update()));
    }

    /** {@code GET /customer/blocked-users}: the blocks in force, newest first; anonymised Users are left out. */
    public ReviewPage<BlockedUser> list(UUID customerId, String cursor, Integer limit) {
        Objects.requireNonNull(customerId, "customerId");
        ReviewCursor after = ReviewCursor.decode(cursor);
        int size = limit == null ? DEFAULT_PAGE_SIZE : limit;
        List<Block> rows = ReviewStore.after(jdbc.sql("""
                                SELECT id, blocked_user_id, blocked_at FROM review.review_author_block
                                 WHERE customer_user_id = :customer AND unblocked_at IS NULL
                                   AND (CAST(:afterAt AS timestamptz) IS NULL
                                        OR (blocked_at, id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                                 ORDER BY blocked_at DESC, id DESC
                                 LIMIT :limit""")
                        .param("customer", customerId).param("limit", size + 1), after)
                .query(ReviewBlocks::block).list();
        boolean more = rows.size() > size;
        List<Block> page = more ? rows.subList(0, size) : rows;
        Map<UUID, TrustCustomers.Customer> named = customers.of(page.stream().map(Block::blockedUserId).toList());
        List<BlockedUser> items = page.stream()
                .filter(row -> named.containsKey(row.blockedUserId()) && !named.get(row.blockedUserId()).deleted())
                .map(row -> new BlockedUser(row.id(), named.get(row.blockedUserId()).username(),
                        row.blockedAt()))
                .toList();
        return new ReviewPage<>(items, more ? new ReviewCursor(page.getLast().blockedAt(), page.getLast().id()).encode()
                : null);
    }

    private static Optional<Block> open(JdbcClient jdbc, UUID customerId, UUID blockedUserId) {
        return jdbc.sql(OPEN).param("customer", customerId).param("blocked", blockedUserId)
                .query(ReviewBlocks::block).optional();
    }

    private static Block block(ResultSet rs, int n) throws SQLException {
        return new Block(rs.getObject("id", UUID.class), rs.getObject("blocked_user_id", UUID.class),
                rs.getObject("blocked_at", OffsetDateTime.class).toInstant());
    }

    private record Block(UUID id, UUID blockedUserId, Instant blockedAt) {
    }

    /**
     * A User the Customer blocked, by current username (PCR-014 item 5), addressed by the block's own id: no user id
     * leaves the server (arch 10 section 5.3, public payloads carry the username only).
     */
    public record BlockedUser(UUID blockId, String username, Instant blockedAt) {
    }
}
