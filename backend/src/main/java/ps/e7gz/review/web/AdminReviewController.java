package ps.e7gz.review.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.ReviewModerationAction;
import ps.e7gz.common.codes.ReviewStatus;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.PageView;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.review.internal.AdminReviews;
import ps.e7gz.review.internal.ReviewRow;

/**
 * Platform Admin - Reviews moderation (arch 09 section 5, FR-REV-08, Spec v5 section 49.7; E7-102). Every action needs
 * a reason and records action, reason, date, previous and new status ({@code review.review_moderation_event}). The
 * Admin sees full name and username (FR-REV-02). No Idempotency-Key: moderation moves no booking, commitment, money or
 * points (PO decision 3 Oct 2026); a repeated action finds the Review already moved and is refused.
 */
@RestController
@RequestMapping("/api/v1/admin/reviews")
@Tag(name = "admin-reviews")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminReviewController {

    private final AdminReviews reviews;
    private final ResponseMetaFactory meta;

    AdminReviewController(AdminReviews reviews, ResponseMetaFactory meta) {
        this.reviews = reviews;
        this.meta = meta;
    }

    @GetMapping
    @Operation(operationId = "adminListReviews", summary = "Reviews in every status (FR-REV-08)",
            description = "Newest first, cursor paging. status, venueId and userId filter exactly; reported=true "
                    + "keeps Reviews with an open Report, reported=false those without.")
    @ApiError(status = 400, codes = ApiErrorCode.VALIDATION_FAILED)
    public DataResponse<PageView<AdminReviewItem>> list(
            @RequestParam(required = false) ReviewStatus status,
            @RequestParam(required = false) UUID venueId,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) Boolean reported,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        var page = reviews.list(status, venueId, userId, reported, cursor, limit);
        return meta.data(new PageView<>(page.items().stream().map(AdminReviewItem::of).toList(), page.nextCursor()));
    }

    @GetMapping("/{reviewId}")
    @Operation(operationId = "adminGetReview", summary = "A Review with its moderation history and Reports",
            description = "History oldest first (FR-REV-08); Reports newest first (FR-REV-11).")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    public DataResponse<AdminReviewDetail> detail(@PathVariable UUID reviewId) {
        AdminReviews.AdminReviewDetail detail = reviews.detail(reviewId);
        return meta.data(new AdminReviewDetail(AdminReviewItem.of(detail.review()),
                detail.moderation().stream().map(AdminReviewModeration::of).toList(),
                detail.reports().stream().map(AdminReviewReportController.AdminReviewReport::of).toList()));
    }

    @PostMapping("/{reviewId}/approve")
    @Operation(operationId = "adminApproveReview", summary = "PENDING_MODERATION → ACTIVE (Spec v5 section 49.6)",
            description = "Notifies the author (REVIEW_APPROVED).")
    @ApiError(status = 400, codes = ApiErrorCode.VALIDATION_FAILED)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED)
    @ApiError(status = 422, codes = ApiErrorCode.REVIEW_REMOVED_NOT_RESTORABLE)
    public DataResponse<AdminReviewModeration> approve(@PathVariable UUID reviewId,
                                                       @Valid @RequestBody AdminReviewModerationRequest request,
                                                       @AuthenticationPrincipal AuthenticatedSubject admin) {
        return moderate(admin, reviewId, ReviewModerationAction.APPROVE, request);
    }

    @PostMapping("/{reviewId}/hide")
    @Operation(operationId = "adminHideReview", summary = "ACTIVE → HIDDEN (Spec v5 section 49.4)",
            description = "No longer public nor in the rating; notifies the author with the reason (REVIEW_HIDDEN).")
    @ApiError(status = 400, codes = ApiErrorCode.VALIDATION_FAILED)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED)
    @ApiError(status = 422, codes = ApiErrorCode.REVIEW_REMOVED_NOT_RESTORABLE)
    public DataResponse<AdminReviewModeration> hide(@PathVariable UUID reviewId,
                                                    @Valid @RequestBody AdminReviewModerationRequest request,
                                                    @AuthenticationPrincipal AuthenticatedSubject admin) {
        return moderate(admin, reviewId, ReviewModerationAction.HIDE, request);
    }

    @PostMapping("/{reviewId}/restore")
    @Operation(operationId = "adminRestoreReview", summary = "HIDDEN → ACTIVE (Spec v5 section 49.4)",
            description = "A REMOVED Review is never restored: REVIEW_REMOVED_NOT_RESTORABLE.")
    @ApiError(status = 400, codes = ApiErrorCode.VALIDATION_FAILED)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED)
    @ApiError(status = 422, codes = ApiErrorCode.REVIEW_REMOVED_NOT_RESTORABLE)
    public DataResponse<AdminReviewModeration> restore(@PathVariable UUID reviewId,
                                                       @Valid @RequestBody AdminReviewModerationRequest request,
                                                       @AuthenticationPrincipal AuthenticatedSubject admin) {
        return moderate(admin, reviewId, ReviewModerationAction.RESTORE, request);
    }

    @PostMapping("/{reviewId}/remove")
    @Operation(operationId = "adminRemoveReview", summary = "ACTIVE, HIDDEN or PENDING_MODERATION → REMOVED",
            description = "Terminal; starts the author's 30-day cooldown for this Venue (Spec v5 section 49.6); "
                    + "notifies the author with the reason (REVIEW_REMOVED).")
    @ApiError(status = 400, codes = ApiErrorCode.VALIDATION_FAILED)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 422, codes = ApiErrorCode.REVIEW_REMOVED_NOT_RESTORABLE)
    public DataResponse<AdminReviewModeration> remove(@PathVariable UUID reviewId,
                                                      @Valid @RequestBody AdminReviewModerationRequest request,
                                                      @AuthenticationPrincipal AuthenticatedSubject admin) {
        return moderate(admin, reviewId, ReviewModerationAction.REMOVE, request);
    }

    private DataResponse<AdminReviewModeration> moderate(AuthenticatedSubject admin, UUID reviewId,
                                                         ReviewModerationAction action,
                                                         AdminReviewModerationRequest request) {
        return meta.data(AdminReviewModeration.of(reviews.moderate(admin.id(), reviewId, action, request.reason())));
    }

    @Schema(name = "AdminReviewModerationRequest")
    public record AdminReviewModerationRequest(
            @NotBlank @Size(max = 500) @Schema(example = "Insults the venue staff.") String reason) {
    }

    @Schema(name = "AdminReviewPerson")
    public record AdminReviewPerson(
            @NotNull UUID userId,
            @NotNull @Schema(example = "Omar Hassan") String fullName,
            @NotNull @Schema(example = "omar_h") String username,
            @NotNull @Schema(description = "Anonymised account (FR-ACC-10).") Boolean deleted) {

        static AdminReviewPerson of(AdminReviews.Person person) {
            return new AdminReviewPerson(person.userId(), person.fullName(), person.username(), person.deleted());
        }
    }

    @Schema(name = "AdminReviewItem")
    public record AdminReviewItem(
            @NotNull UUID reviewId,
            @NotNull UUID venueId,
            @NotNull String venueName,
            @NotNull AdminReviewPerson author,
            @NotNull Integer rating,
            @NotNull String comment,
            @NotNull ReviewStatus status,
            @NotNull @Schema(description = "REMOVED by its author (Spec v5 section 49.5), not by moderation.")
            Boolean deletedByAuthor,
            @NotNull @Schema(description = "Unresolved Reports.") Integer openReports,
            @NotNull Instant createdAt,
            @NotNull Instant updatedAt) {

        static AdminReviewItem of(AdminReviews.AdminReview review) {
            ReviewRow row = review.review();
            return new AdminReviewItem(row.id(), row.venueId(), review.venueName(),
                    AdminReviewPerson.of(review.author()), row.rating(), row.comment(), row.status(),
                    row.authorDeleted(), review.openReports(), row.createdAt(), row.updatedAt());
        }
    }

    @Schema(name = "AdminReviewModeration")
    public record AdminReviewModeration(
            @NotNull UUID moderationEventId,
            @NotNull UUID reviewId,
            @NotNull ReviewModerationAction action,
            @NotNull ReviewStatus previousStatus,
            @NotNull ReviewStatus newStatus,
            @NotNull UUID adminId,
            @NotNull String reason,
            @NotNull Instant occurredAt) {

        static AdminReviewModeration of(AdminReviews.Moderation m) {
            return new AdminReviewModeration(m.id(), m.reviewId(), m.action(), m.previousStatus(), m.newStatus(),
                    m.adminId(), m.reason(), m.occurredAt());
        }
    }

    @Schema(name = "AdminReviewDetail")
    public record AdminReviewDetail(
            @NotNull AdminReviewItem review,
            @NotNull List<AdminReviewModeration> moderation,
            @NotNull List<AdminReviewReportController.AdminReviewReport> reports) {
    }
}
