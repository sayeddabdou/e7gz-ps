package ps.e7gz.review.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.NotificationRecipientType;
import ps.e7gz.common.codes.ReviewModerationAction;
import ps.e7gz.common.codes.ReviewStatus;
import ps.e7gz.common.command.CommandContext;
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
 * Platform Admin moderation and Review Reports (Spec v5 sections 49.4, 49.6, 49.7, 49.9, 49.10; FR-REV-04, FR-REV-07,
 * FR-REV-08, FR-REV-10, FR-REV-11; ADR-025; O20; arch 09 section 5).
 *
 * <p>Moderation is one Admin command under the author's and the Venue's locks ({@code review.moderate} requires both
 * in the Admin command scope) with a mandatory reason. The database writes the status, the moderation event, the audit,
 * the cooldown of a REMOVE, the rating and the {@code ReviewModerated} outbox event; the author's notification is
 * written here, in the same transaction: APPROVE {@code REVIEW_APPROVED}, HIDE {@code REVIEW_HIDDEN} and REMOVE
 * {@code REVIEW_REMOVED} with the reason (section 49.9); RESTORE has none (section 49.9 does not list it). Any action
 * on a REMOVED Review is {@code REVIEW_REMOVED_NOT_RESTORABLE} (terminal); any other action its state does not allow is
 * {@code ADMIN_ACTION_NOT_ALLOWED}.
 *
 * <p>A Report is resolved once, with a mandatory note, through {@code review.resolve_report}; the linked moderation
 * event, when given, must be of the same Review ({@code VALIDATION_FAILED} on {@code moderationEventId}). The same
 * resolution sent again by the same Admin answers the resolved Report; any other on a resolved Report is
 * {@code ADMIN_ACTION_NOT_ALLOWED}. Resolving changes no Review.
 */
@Component
public class AdminReviews {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_REASON = 500;
    static final int MAX_NOTE = 1000;

    private static final String OPEN_REPORTS = """
            (SELECT count(*) FROM review.review_report x WHERE x.review_id = r.id AND x.resolved_at IS NULL)
            """;

    private static final String REPORT_COLUMNS = """
            x.id, x.review_id, x.reporter_user_id, x.reason, x.details, x.created_at, x.resolved_at,
            x.resolved_by_admin_id, x.resolution_note, x.moderation_event_id
            """;

    private final JdbcClient jdbc;
    private final CommandRunner runner;
    private final TrustCustomers customers;
    private final VenueDiscovery venues;
    private final Notifications notifications;

    AdminReviews(DataSource appDataSource, CommandRunner runner, TrustCustomers customers, VenueDiscovery venues,
                 Notifications notifications) {
        this.jdbc = JdbcClient.create(appDataSource);
        this.runner = runner;
        this.customers = customers;
        this.venues = venues;
        this.notifications = notifications;
    }

    // ------------------------------------------------------------------------------------------------ reviews

    /** {@code GET /admin/reviews}: every status, newest first; {@code reported} filters on an open Report. */
    public ReviewPage<AdminReview> list(ReviewStatus status, UUID venueId, UUID userId, Boolean reported,
                                        String cursor, Integer limit) {
        ReviewCursor after = ReviewCursor.decode(cursor);
        int size = limit == null ? DEFAULT_PAGE_SIZE : limit;
        List<Listed> rows = ReviewStore.after(jdbc.sql("SELECT " + ReviewStore.COLUMNS + ", " + OPEN_REPORTS
                                + """
                                AS open_reports
                                  FROM review.review r
                                 WHERE (CAST(:status AS text) IS NULL OR r.status = CAST(:status AS text))
                                   AND (CAST(:venue AS uuid) IS NULL OR r.venue_id = CAST(:venue AS uuid))
                                   AND (CAST(:user AS uuid) IS NULL OR r.user_id = CAST(:user AS uuid))
                                   AND (CAST(:reported AS boolean) IS NULL OR CAST(:reported AS boolean) = EXISTS (
                                        SELECT 1 FROM review.review_report x
                                         WHERE x.review_id = r.id AND x.resolved_at IS NULL))
                                   AND (CAST(:afterAt AS timestamptz) IS NULL
                                        OR (r.created_at, r.id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                                 ORDER BY r.created_at DESC, r.id DESC
                                 LIMIT :limit""")
                        .param("status", status == null ? null : status.name())
                        .param("venue", venueId).param("user", userId).param("reported", reported)
                        .param("limit", size + 1), after)
                .query((rs, n) -> new Listed(ReviewStore.row(rs, n), rs.getInt("open_reports")))
                .list();
        boolean more = rows.size() > size;
        List<Listed> page = more ? rows.subList(0, size) : rows;
        List<AdminReview> items = named(page);
        ReviewRow last = page.isEmpty() ? null : page.getLast().row();
        return new ReviewPage<>(items, more ? new ReviewCursor(last.createdAt(), last.id()).encode() : null);
    }

    /** {@code GET /admin/reviews/{reviewId}}: the Review, its moderation history (oldest first) and its Reports. */
    public AdminReviewDetail detail(UUID reviewId) {
        Objects.requireNonNull(reviewId, "reviewId");
        Listed listed = jdbc.sql("SELECT " + ReviewStore.COLUMNS + ", " + OPEN_REPORTS
                        + " AS open_reports FROM review.review r WHERE r.id = :id")
                .param("id", reviewId)
                .query((rs, n) -> new Listed(ReviewStore.row(rs, n), rs.getInt("open_reports")))
                .optional().orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        List<Moderation> history = jdbc.sql("""
                        SELECT id, review_id, action, previous_status, new_status, admin_id, reason, occurred_at
                          FROM review.review_moderation_event WHERE review_id = :id
                         ORDER BY occurred_at, id""")
                .param("id", reviewId).query(AdminReviews::moderation).list();
        List<Report> reports = reports(jdbc.sql("SELECT " + REPORT_COLUMNS + """
                         FROM review.review_report x WHERE x.review_id = :id
                        ORDER BY x.created_at DESC, x.id DESC""").param("id", reviewId)
                .query(AdminReviews::reportRow).list());
        return new AdminReviewDetail(named(List.of(listed)).getFirst(), history, reports);
    }

    /** {@code POST /admin/reviews/{reviewId}/approve|hide|restore|remove}. */
    public Moderation moderate(UUID adminId, UUID reviewId, ReviewModerationAction action, String reason) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(reviewId, "reviewId");
        Objects.requireNonNull(action, "action");
        String why = ReviewReports.text(reason, MAX_REASON, "reason", true);
        ReviewRow review = ReviewStore.find(jdbc, reviewId).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        CommandScope scope = CommandScope.admin(adminId, CorrelationId.require(),
                LockSet.builder().users(review.userId()).venues(review.venueId()).build());
        return runner.run(new ReviewCommand<>(scope, context -> {
            ReviewRow now = ReviewStore.find(context.jdbc(), reviewId).orElseThrow();
            if (now.status() == ReviewStatus.REMOVED) {
                throw new ApiException(ApiErrorCode.REVIEW_REMOVED_NOT_RESTORABLE);    // terminal (section 49.4)
            }
            if (!allowed(action, now.status())) {
                throw new ApiException(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);
            }
            UUID event = context.jdbc().sql("SELECT review.moderate(:review, :action, :reason)")
                    .param("review", reviewId).param("action", action.name()).param("reason", why)
                    .query(UUID.class).single();
            notifyAuthor(context, now, action, why);
            return context.jdbc().sql("""
                            SELECT id, review_id, action, previous_status, new_status, admin_id, reason, occurred_at
                              FROM review.review_moderation_event WHERE id = :id""")
                    .param("id", event).query(AdminReviews::moderation).single();
        }));
    }

    /** The transitions of {@code ck_review_moderation_transition} from a non-REMOVED status. */
    static boolean allowed(ReviewModerationAction action, ReviewStatus from) {
        return switch (action) {
            case APPROVE -> from == ReviewStatus.PENDING_MODERATION;
            case HIDE -> from == ReviewStatus.ACTIVE;
            case RESTORE -> from == ReviewStatus.HIDDEN;
            case REMOVE -> from != ReviewStatus.REMOVED;
        };
    }

    private void notifyAuthor(CommandContext context, ReviewRow review, ReviewModerationAction action, String reason) {
        String code = switch (action) {
            case APPROVE -> "REVIEW_APPROVED";
            case HIDE -> "REVIEW_HIDDEN";
            case REMOVE -> "REVIEW_REMOVED";
            case RESTORE -> null;
        };
        if (code == null) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("reviewId", review.id().toString());
        params.put("venueId", review.venueId().toString());
        if (action != ReviewModerationAction.APPROVE) {
            params.put("reason", reason);                                       // section 49.9 "where appropriate"
        }
        notifications.notify(context, NotificationRequest.of(code, NotificationRecipientType.CUSTOMER,
                review.userId(), params));
    }

    // ------------------------------------------------------------------------------------------------ reports

    /** {@code GET /admin/review-reports}: newest first; {@code resolved} and {@code reviewId} filter exactly. */
    public ReviewPage<Report> reports(Boolean resolved, UUID reviewId, String cursor, Integer limit) {
        ReviewCursor after = ReviewCursor.decode(cursor);
        int size = limit == null ? DEFAULT_PAGE_SIZE : limit;
        List<ReportRow> rows = ReviewStore.after(jdbc.sql("SELECT " + REPORT_COLUMNS + """
                                  FROM review.review_report x
                                 WHERE (CAST(:resolved AS boolean) IS NULL
                                        OR (x.resolved_at IS NOT NULL) = CAST(:resolved AS boolean))
                                   AND (CAST(:review AS uuid) IS NULL OR x.review_id = CAST(:review AS uuid))
                                   AND (CAST(:afterAt AS timestamptz) IS NULL
                                        OR (x.created_at, x.id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                                 ORDER BY x.created_at DESC, x.id DESC
                                 LIMIT :limit""")
                        .param("resolved", resolved).param("review", reviewId).param("limit", size + 1), after)
                .query(AdminReviews::reportRow).list();
        boolean more = rows.size() > size;
        List<ReportRow> page = more ? rows.subList(0, size) : rows;
        ReportRow last = page.isEmpty() ? null : page.getLast();
        return new ReviewPage<>(reports(page), more ? new ReviewCursor(last.createdAt(), last.id()).encode() : null);
    }

    /** {@code POST /admin/review-reports/{reportId}/resolve}. */
    public Report resolve(UUID adminId, UUID reportId, String resolutionNote, UUID moderationEventId) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(reportId, "reportId");
        String note = ReviewReports.text(resolutionNote, MAX_NOTE, "resolutionNote", true);
        CommandScope scope = CommandScope.admin(adminId, CorrelationId.require(), LockSet.empty());
        ReportRow resolved = runner.run(new ReviewCommand<>(scope, context -> {
            // Serialises resolutions of one Report: the runtime roles have no UPDATE on this producer-only table, so
            // no FOR UPDATE; review.resolve_report locks the row itself.
            context.jdbc().sql("SELECT pg_advisory_xact_lock(hashtextextended('e7gz.review_report_resolve:' || :id, 0))")
                    .param("id", reportId.toString()).query().singleRow();
            ReportRow report = context.jdbc().sql("SELECT " + REPORT_COLUMNS
                            + " FROM review.review_report x WHERE x.id = :id")
                    .param("id", reportId).query(AdminReviews::reportRow).optional()
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            if (report.resolvedAt() != null) {
                if (adminId.equals(report.resolvedByAdminId())
                        && Objects.equals(moderationEventId, report.moderationEventId())) {
                    return report;                                             // the same resolution again
                }
                throw new ApiException(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);
            }
            if (moderationEventId != null && context.jdbc().sql("""
                            SELECT count(*) FROM review.review_moderation_event WHERE id = :event AND review_id = :review""")
                    .param("event", moderationEventId).param("review", report.reviewId())
                    .query(Long.class).single() == 0) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "moderationEventId");   // arch 10 section 5.3a
            }
            context.jdbc().sql("SELECT review.resolve_report(:report, :note, CAST(:event AS uuid))")
                    .param("report", reportId).param("note", note).param("event", moderationEventId)
                    .query(UUID.class).single();
            return context.jdbc().sql("SELECT " + REPORT_COLUMNS + " FROM review.review_report x WHERE x.id = :id")
                    .param("id", reportId).query(AdminReviews::reportRow).single();
        }));
        return reports(List.of(resolved)).getFirst();
    }

    // ------------------------------------------------------------------------------------------------ naming

    private List<AdminReview> named(List<Listed> rows) {
        Map<UUID, TrustCustomers.Customer> authors = customers.of(rows.stream().map(l -> l.row().userId()).toList());
        Set<UUID> venueIds = rows.stream().map(l -> l.row().venueId()).collect(Collectors.toSet());
        Map<UUID, String> venueNames = new HashMap<>();
        venueIds.forEach(id -> venueNames.put(id, venues.profile(id, null).map(VenueDiscovery.Profile::name)
                .orElse("")));
        return rows.stream().map(l -> new AdminReview(l.row(), venueNames.get(l.row().venueId()),
                person(authors, l.row().userId()), l.openReports())).toList();
    }

    private List<Report> reports(List<ReportRow> rows) {
        Map<UUID, TrustCustomers.Customer> reporters = customers.of(rows.stream().map(ReportRow::reporterId).toList());
        return rows.stream().map(row -> new Report(row, person(reporters, row.reporterId()))).toList();
    }

    private static Person person(Map<UUID, TrustCustomers.Customer> named, UUID userId) {
        TrustCustomers.Customer customer = named.get(userId);
        return customer == null ? new Person(userId, "", "", false)
                : new Person(userId, customer.fullName(), customer.username(), customer.deleted());
    }

    private static Moderation moderation(ResultSet rs, int n) throws SQLException {
        return new Moderation(rs.getObject("id", UUID.class), rs.getObject("review_id", UUID.class),
                ReviewModerationAction.valueOf(rs.getString("action")),
                ReviewStatus.valueOf(rs.getString("previous_status")), ReviewStatus.valueOf(rs.getString("new_status")),
                rs.getObject("admin_id", UUID.class), rs.getString("reason"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant());
    }

    private static ReportRow reportRow(ResultSet rs, int n) throws SQLException {
        OffsetDateTime resolvedAt = rs.getObject("resolved_at", OffsetDateTime.class);
        return new ReportRow(rs.getObject("id", UUID.class), rs.getObject("review_id", UUID.class),
                rs.getObject("reporter_user_id", UUID.class), rs.getString("reason"), rs.getString("details"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                Optional.ofNullable(resolvedAt).map(OffsetDateTime::toInstant).orElse(null),
                rs.getObject("resolved_by_admin_id", UUID.class), rs.getString("resolution_note"),
                rs.getObject("moderation_event_id", UUID.class));
    }

    private record Listed(ReviewRow row, int openReports) {
    }

    /** The Admin sees both names (FR-REV-02); {@code deleted} is an anonymised account (FR-ACC-10). */
    public record Person(UUID userId, String fullName, String username, boolean deleted) {
    }

    public record AdminReview(ReviewRow review, String venueName, Person author, int openReports) {
    }

    public record AdminReviewDetail(AdminReview review, List<Moderation> moderation, List<Report> reports) {
    }

    public record Moderation(UUID id, UUID reviewId, ReviewModerationAction action, ReviewStatus previousStatus,
                             ReviewStatus newStatus, UUID adminId, String reason, Instant occurredAt) {
    }

    public record ReportRow(UUID id, UUID reviewId, UUID reporterId, String reason, String details, Instant createdAt,
                            Instant resolvedAt, UUID resolvedByAdminId, String resolutionNote,
                            UUID moderationEventId) {
    }

    public record Report(ReportRow report, Person reporter) {
    }
}
