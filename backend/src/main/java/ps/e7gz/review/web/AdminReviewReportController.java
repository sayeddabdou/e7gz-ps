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
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.PageView;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.review.internal.AdminReviews;

/**
 * Platform Admin - Review Reports (arch 09 section 5, FR-REV-11, PCR-003, ADR-025, O20; E7-102). A Report is resolved
 * once with a mandatory note and may link a moderation event of the same Review (arch 10 section 5.3a); resolving
 * changes no Review.
 */
@RestController
@RequestMapping("/api/v1/admin/review-reports")
@Tag(name = "admin-review-reports")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminReviewReportController {

    private final AdminReviews reviews;
    private final ResponseMetaFactory meta;

    AdminReviewReportController(AdminReviews reviews, ResponseMetaFactory meta) {
        this.reviews = reviews;
        this.meta = meta;
    }

    @GetMapping
    @Operation(operationId = "adminListReviewReports", summary = "Review Reports (FR-REV-11)",
            description = "Newest first, cursor paging; resolved and reviewId filter exactly.")
    @ApiError(status = 400, codes = ApiErrorCode.VALIDATION_FAILED)
    public DataResponse<PageView<AdminReviewReport>> list(
            @RequestParam(required = false) Boolean resolved,
            @RequestParam(required = false) UUID reviewId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        var page = reviews.reports(resolved, reviewId, cursor, limit);
        return meta.data(new PageView<>(page.items().stream().map(AdminReviewReport::of).toList(), page.nextCursor()));
    }

    @PostMapping("/{reportId}/resolve")
    @Operation(operationId = "adminResolveReviewReport", summary = "Resolve a Review Report (O20)",
            description = "A resolution note is required; moderationEventId, when given, must be a moderation event "
                    + "of the reported Review (VALIDATION_FAILED otherwise). Resolved once: the same resolution sent "
                    + "again answers the resolved Report, any other is ADMIN_ACTION_NOT_ALLOWED.")
    @ApiError(status = 400, codes = ApiErrorCode.VALIDATION_FAILED)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED)
    public DataResponse<AdminReviewReport> resolve(@PathVariable UUID reportId,
                                                   @Valid @RequestBody AdminReviewReportResolveRequest request,
                                                   @AuthenticationPrincipal AuthenticatedSubject admin) {
        return meta.data(AdminReviewReport.of(reviews.resolve(admin.id(), reportId, request.resolutionNote(),
                request.moderationEventId())));
    }

    @Schema(name = "AdminReviewReportResolveRequest")
    public record AdminReviewReportResolveRequest(
            @NotBlank @Size(max = 1000) @Schema(example = "Removed the review for abusive language.")
            String resolutionNote,
            @Schema(nullable = true, description = "A moderation event of the same Review.") UUID moderationEventId) {
    }

    @Schema(name = "AdminReviewReport")
    public record AdminReviewReport(
            @NotNull UUID reportId,
            @NotNull UUID reviewId,
            @NotNull AdminReviewController.AdminReviewPerson reporter,
            @NotNull String reason,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String details,
            @NotNull Instant createdAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Instant resolvedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID resolvedByAdminId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String resolutionNote,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID moderationEventId) {

        static AdminReviewReport of(AdminReviews.Report report) {
            AdminReviews.ReportRow r = report.report();
            return new AdminReviewReport(r.id(), r.reviewId(), AdminReviewController.AdminReviewPerson.of(report.reporter()),
                    r.reason(), r.details(), r.createdAt(), r.resolvedAt(), r.resolvedByAdminId(), r.resolutionNote(),
                    r.moderationEventId());
        }
    }
}
