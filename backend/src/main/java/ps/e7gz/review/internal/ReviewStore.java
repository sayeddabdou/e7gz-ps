package ps.e7gz.review.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.codes.ReviewStatus;

/**
 * The review module's SQL. Reads only: every write goes through a {@code review.*} producer function (arch 04
 * section 2.2), called by the command classes; the block table (v1.1.47) is written by {@link ReviewBlocks}.
 */
final class ReviewStore {

    static final String COLUMNS = """
            r.id, r.user_id, r.venue_id, r.rating, r.comment, r.status, r.author_deleted_at IS NOT NULL AS author_deleted,
            r.created_at, r.updated_at
            """;

    /**
     * PCR-014 (FR-REV-12): leaves out the Reviews of Users the reader has blocked; nothing else changes. Every customer
     * read of Reviews uses it.
     */
    static final String NOT_BLOCKED_BY_READER = """
            NOT EXISTS (SELECT 1 FROM review.review_author_block b
                         WHERE b.customer_user_id = :reader AND b.blocked_user_id = r.user_id
                           AND b.unblocked_at IS NULL)""";

    private ReviewStore() {
    }

    static Optional<ReviewRow> find(JdbcClient jdbc, UUID reviewId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM review.review r WHERE r.id = :id")
                .param("id", reviewId).query(ReviewStore::row).optional();
    }

    /** The one non-REMOVED Review of the User for the Venue ({@code ux_review_current}). */
    static Optional<ReviewRow> current(JdbcClient jdbc, UUID userId, UUID venueId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM review.review r
                        WHERE r.user_id = :user AND r.venue_id = :venue AND r.status <> 'REMOVED'""")
                .param("user", userId).param("venue", venueId).query(ReviewStore::row).optional();
    }

    /** ACTIVE Reviews of a Venue, newest first; with a reader, without the authors that reader blocked. */
    static List<ReviewRow> publicPage(JdbcClient jdbc, UUID venueId, UUID reader, ReviewCursor after, int limit) {
        String blocks = reader == null ? "" : " AND " + NOT_BLOCKED_BY_READER;
        JdbcClient.StatementSpec sql = jdbc.sql("SELECT " + COLUMNS + """
                         FROM review.review r
                        WHERE r.venue_id = :venue AND r.status = 'ACTIVE'
                          AND (CAST(:afterAt AS timestamptz) IS NULL
                               OR (r.created_at, r.id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))"""
                        + blocks + """

                        ORDER BY r.created_at DESC, r.id DESC
                        LIMIT :limit""")
                .param("venue", venueId)
                .param("limit", limit);
        if (reader != null) {
            sql = sql.param("reader", reader);
        }
        return after(sql, after).query(ReviewStore::row).list();
    }

    static JdbcClient.StatementSpec after(JdbcClient.StatementSpec sql, ReviewCursor after) {
        return sql.param("afterAt", after == null ? null : OffsetDateTime.ofInstant(after.at(), ZoneOffset.UTC))
                .param("afterId", after == null ? null : after.id());
    }

    static ReviewRow row(ResultSet rs, int n) throws SQLException {
        return new ReviewRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("venue_id", UUID.class), rs.getInt("rating"), rs.getString("comment"),
                ReviewStatus.valueOf(rs.getString("status")), rs.getBoolean("author_deleted"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
