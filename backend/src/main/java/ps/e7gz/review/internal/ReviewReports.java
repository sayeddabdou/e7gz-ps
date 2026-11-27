package ps.e7gz.review.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.ReviewStatus;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;

/**
 * A Customer reports a public Review (PCR-003, FR-REV-11, ADR-025; O20 submission). The command runs under the
 * reporter's and the Venue's locks and calls {@code review.report_review}, which writes the report and its audit and
 * refuses a second open report by the same reporter ({@code REVIEW_REPORT_ALREADY_OPEN}). Nothing else happens: no
 * status change, no rating change, no moderation call, no alert, no auto-hide after any number of reports.
 *
 * <p>Only a Review the reporter can see is reported (arch 10 section 5.3a): ACTIVE, and not by an author the reporter
 * blocked (PCR-014); otherwise {@code NOT_FOUND}, as for a Review that does not exist. A Customer may report their own
 * Review (PO decision 26 Sep 2026, "review self-reporting allowed"). The reason and details are untrusted text: stored
 * trimmed, never logged.
 */
@Component
public class ReviewReports {

    static final int MAX_REASON = 500;
    static final int MAX_DETAILS = 2000;

    private final JdbcClient jdbc;
    private final CommandRunner runner;

    ReviewReports(DataSource appDataSource, CommandRunner runner) {
        this.jdbc = JdbcClient.create(appDataSource);
        this.runner = runner;
    }

    /** {@code POST /customer/reviews/{reviewId}/report}. */
    public SubmittedReport report(UUID reporterId, UUID reviewId, String reason, String details) {
        Objects.requireNonNull(reporterId, "reporterId");
        Objects.requireNonNull(reviewId, "reviewId");
        String why = text(reason, MAX_REASON, "reason", true);
        String more = text(details, MAX_DETAILS, "details", false);
        ReviewRow review = ReviewStore.find(jdbc, reviewId).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        UUID correlation = CorrelationId.require();
        CommandScope scope = CommandScope.shared(LockSet.builder().users(reporterId).venues(review.venueId()).build());
        return runner.run(new ReviewCommand<>(scope, context -> {
            boolean visible = context.jdbc().sql("SELECT count(*) FROM review.review r WHERE r.id = :review"
                            + " AND r.status = :active AND " + ReviewStore.NOT_BLOCKED_BY_READER)
                    .param("review", reviewId).param("active", ReviewStatus.ACTIVE.name()).param("reader", reporterId)
                    .query(Long.class).single() == 1;
            if (!visible) {
                throw new ApiException(ApiErrorCode.NOT_FOUND);
            }
            UUID id = context.jdbc().sql("SELECT review.report_review(:review, :reporter, :reason, :details, :correlation)")
                    .param("review", reviewId).param("reporter", reporterId).param("reason", why)
                    .param("details", more).param("correlation", correlation)
                    .query(UUID.class).single();
            Instant at = context.jdbc().sql("SELECT created_at FROM review.review_report WHERE id = :id")
                    .param("id", id).query(OffsetDateTime.class).single().toInstant();
            return new SubmittedReport(id, reviewId, at);
        }));
    }

    /** Trimmed; blank is VALIDATION_FAILED when required, null when optional; too long is VALIDATION_FAILED. */
    static String text(String value, int max, String field, boolean required) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) {
            if (required) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED, field);
            }
            return null;
        }
        if (text.length() > max) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, field);
        }
        return text;
    }

    public record SubmittedReport(UUID reportId, UUID reviewId, Instant createdAt) {
    }
}
