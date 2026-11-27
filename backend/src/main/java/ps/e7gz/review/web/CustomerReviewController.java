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
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.ReviewStatus;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.PageView;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.review.internal.CustomerReviews;
import ps.e7gz.review.internal.ReviewBlocks;
import ps.e7gz.review.internal.ReviewReports;
import ps.e7gz.review.internal.ReviewRow;

/**
 * Customer - Reviews of a Venue (arch 09 sections 3.2 and 3.4; Spec v5 section 49; E7-102). The Customer DTOs are
 * their own (arch 09 section 1.1): a public Review carries the author's current username only (FR-REV-02, arch 10
 * section 5.3), never a name or a user id. No Idempotency-Key: a Review moves no booking, commitment, money or points
 * (CLAUDE.md section 5, PO decision 3 Oct 2026); a repeated create is {@code REVIEW_ALREADY_EXISTS}.
 *
 * <p>The list is for a signed-in Customer only: it leaves out the Reviews of Users the reader blocked (PCR-014), so it
 * needs the reader. The Venue's rating and review count are on the public venue details.
 */
@RestController
@RequestMapping("/api/v1/customer")
@Tag(name = "customer-reviews")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class CustomerReviewController {

    private final CustomerReviews reviews;
    private final ReviewReports reports;
    private final ReviewBlocks blocks;
    private final ResponseMetaFactory meta;

    CustomerReviewController(CustomerReviews reviews, ReviewReports reports, ReviewBlocks blocks,
                             ResponseMetaFactory meta) {
        this.reviews = reviews;
        this.reports = reports;
        this.blocks = blocks;
        this.meta = meta;
    }

    @GetMapping("/venues/{venueId}/reviews")
    @Operation(operationId = "customerListVenueReviews", summary = "A Venue's public Reviews (FR-REV-02, PCR-014)",
            description = "ACTIVE Reviews only, newest first, cursor paging; the author's current username only. "
                    + "Reviews by Users the reader blocked are left out; nothing else changes (the rating included).")
    @ApiError(status = 400, codes = ApiErrorCode.VALIDATION_FAILED)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    public DataResponse<PageView<CustomerVenueReview>> list(
            @PathVariable UUID venueId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            @AuthenticationPrincipal AuthenticatedSubject customer) {
        var page = reviews.list(customer.id(), venueId, cursor, limit);
        return meta.data(new PageView<>(page.items().stream().map(CustomerVenueReview::of).toList(),
                page.nextCursor()));
    }

    @GetMapping("/venues/{venueId}/review")
    @Operation(operationId = "customerGetMyVenueReview", summary = "My current Review of the Venue (FR-REV-03)",
            description = "Any status but REMOVED: PENDING_MODERATION is shown to its author as pending, HIDDEN as "
                    + "hidden (Spec v5 section 49.4). NOT_FOUND when there is none.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    public DataResponse<CustomerMyReview> mine(@PathVariable UUID venueId,
                                               @AuthenticationPrincipal AuthenticatedSubject customer) {
        return meta.data(CustomerMyReview.of(reviews.mine(customer.id(), venueId)));
    }

    @PostMapping("/venues/{venueId}/review")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "customerCreateVenueReview", summary = "Review a Venue (FR-REV-01, FR-REV-05, FR-REV-07)",
            description = "An account-verified Customer; no booking needed; a banned one too (FR-ACC-17). Starts "
                    + "ACTIVE, or PENDING_MODERATION after an earlier Admin removal once the 30-day cooldown has passed. "
                    + "Any existing Venue, whatever its status (FR-REV-09).")
    @ApiError(status = 400, codes = ApiErrorCode.VALIDATION_FAILED)
    @ApiError(status = 403, codes = {ApiErrorCode.PHONE_VERIFICATION_REQUIRED, ApiErrorCode.ACCOUNT_DELETED})
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 422, codes = {ApiErrorCode.REVIEW_ALREADY_EXISTS, ApiErrorCode.REVIEW_COOLDOWN_ACTIVE})
    public DataResponse<CustomerMyReview> create(@PathVariable UUID venueId,
                                                 @Valid @RequestBody CustomerReviewRequest request,
                                                 @AuthenticationPrincipal AuthenticatedSubject customer) {
        return meta.data(CustomerMyReview.of(
                reviews.create(customer.id(), venueId, request.rating(), request.comment())));
    }

    @PatchMapping("/venues/{venueId}/review")
    @Operation(operationId = "customerEditVenueReview", summary = "Edit my Review of the Venue (FR-REV-03)",
            description = "Rating and comment; the status is kept - a HIDDEN Review stays HIDDEN (Spec v5 section "
                    + "49.4). NOT_FOUND when there is no current Review.")
    @ApiError(status = 400, codes = ApiErrorCode.VALIDATION_FAILED)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    public DataResponse<CustomerMyReview> edit(@PathVariable UUID venueId,
                                               @Valid @RequestBody CustomerReviewRequest request,
                                               @AuthenticationPrincipal AuthenticatedSubject customer) {
        return meta.data(CustomerMyReview.of(
                reviews.edit(customer.id(), venueId, request.rating(), request.comment())));
    }

    @DeleteMapping("/venues/{venueId}/review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "customerDeleteVenueReview", summary = "Delete my Review of the Venue (FR-REV-06)",
            description = "No longer public; its moderation history is kept, and no cooldown starts (Spec v5 "
                    + "section 49.5). NOT_FOUND when there is no current Review.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    public void delete(@PathVariable UUID venueId, @AuthenticationPrincipal AuthenticatedSubject customer) {
        reviews.delete(customer.id(), venueId);
    }

    @PostMapping("/reviews/{reviewId}/report")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "customerReportReview", summary = "Report a public Review (FR-REV-11, PCR-003)",
            description = "A reason and optional details. Only a Review the Customer can see: ACTIVE and not by an "
                    + "author they blocked; otherwise NOT_FOUND. The report never changes the Review or the rating "
                    + "(ADR-025). One open report per Customer per Review.")
    @ApiError(status = 400, codes = ApiErrorCode.VALIDATION_FAILED)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 422, codes = ApiErrorCode.REVIEW_REPORT_ALREADY_OPEN)
    public DataResponse<CustomerReviewReport> report(@PathVariable UUID reviewId,
                                                     @Valid @RequestBody CustomerReviewReportRequest request,
                                                     @AuthenticationPrincipal AuthenticatedSubject customer) {
        ReviewReports.SubmittedReport report = reports.report(customer.id(), reviewId, request.reason(),
                request.details());
        return meta.data(new CustomerReviewReport(report.reportId(), report.reviewId(), report.createdAt()));
    }

    @PostMapping("/reviews/{reviewId}/block-author")
    @Operation(operationId = "customerBlockReviewAuthor", summary = "Block the author of a Review (PCR-014)",
            description = "The Customer then sees no Review by that User, on any Venue. Nobody else is affected and "
                    + "the author is never told. Blocking a User already blocked answers the same block. Your own "
                    + "Review is VALIDATION_FAILED; a Review you cannot see is NOT_FOUND.")
    @ApiError(status = 400, codes = ApiErrorCode.VALIDATION_FAILED)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    public DataResponse<CustomerBlockedUser> blockAuthor(@PathVariable UUID reviewId,
                                                         @AuthenticationPrincipal AuthenticatedSubject customer) {
        return meta.data(CustomerBlockedUser.of(blocks.blockAuthor(customer.id(), reviewId)));
    }

    @GetMapping("/blocked-users")
    @Operation(operationId = "customerListBlockedUsers", summary = "The Users I blocked (PCR-014 item 5)",
            description = "Blocks in force, newest first, cursor paging; current username only.")
    @ApiError(status = 400, codes = ApiErrorCode.VALIDATION_FAILED)
    public DataResponse<PageView<CustomerBlockedUser>> blockedUsers(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            @AuthenticationPrincipal AuthenticatedSubject customer) {
        var page = blocks.list(customer.id(), cursor, limit);
        return meta.data(new PageView<>(page.items().stream().map(CustomerBlockedUser::of).toList(),
                page.nextCursor()));
    }

    @PostMapping("/blocked-users/{blockId}/unblock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "customerUnblockUser", summary = "Unblock a User (PCR-014 item 5)",
            description = "The blocked User's Reviews are shown again. A block already lifted, or not yours, changes "
                    + "nothing.")
    public void unblock(@PathVariable UUID blockId, @AuthenticationPrincipal AuthenticatedSubject customer) {
        blocks.unblock(customer.id(), blockId);
    }

    @Schema(name = "CustomerReviewReportRequest")
    public record CustomerReviewReportRequest(
            @NotBlank @Size(max = 500) @Schema(example = "Abusive language") String reason,
            @Size(max = 2000) @Schema(nullable = true, example = "Insults the staff by name.") String details) {
    }

    @Schema(name = "CustomerReviewReport")
    public record CustomerReviewReport(@NotNull UUID reportId, @NotNull UUID reviewId, @NotNull Instant createdAt) {
    }

    @Schema(name = "CustomerBlockedUser")
    public record CustomerBlockedUser(
            @NotNull @Schema(description = "The block itself; unblock with it. No user id is ever shown.") UUID blockId,
            @NotNull @Schema(example = "omar_k") String username,
            @NotNull Instant blockedAt) {

        static CustomerBlockedUser of(ReviewBlocks.BlockedUser user) {
            return new CustomerBlockedUser(user.blockId(), user.username(), user.blockedAt());
        }
    }

    @Schema(name = "CustomerReviewRequest")
    public record CustomerReviewRequest(
            @NotNull @Min(1) @Max(5) @Schema(example = "4") Integer rating,
            @NotBlank @Size(max = 2000) @Schema(example = "Clean rooms and fast controllers.") String comment) {
    }

    /** The caller's own Review: its status is shown to its author only. */
    @Schema(name = "CustomerMyReview")
    public record CustomerMyReview(
            @NotNull UUID reviewId,
            @NotNull UUID venueId,
            @NotNull @Schema(example = "4") Integer rating,
            @NotNull String comment,
            @NotNull @Schema(description = "PENDING_MODERATION, ACTIVE or HIDDEN.") ReviewStatus status,
            @NotNull Instant createdAt,
            @NotNull Instant updatedAt) {

        static CustomerMyReview of(ReviewRow row) {
            return new CustomerMyReview(row.id(), row.venueId(), row.rating(), row.comment(), row.status(),
                    row.createdAt(), row.updatedAt());
        }
    }

    /** A public Review: the current username only (FR-REV-02). {@code mine} marks the reader's own Review. */
    @Schema(name = "CustomerVenueReview")
    public record CustomerVenueReview(
            @NotNull UUID reviewId,
            @NotNull @Schema(example = "omar_k") String username,
            @NotNull @Schema(example = "5") Integer rating,
            @NotNull String comment,
            @NotNull Instant createdAt,
            @NotNull Instant updatedAt,
            @NotNull Boolean mine) {

        static CustomerVenueReview of(CustomerReviews.PublicReview review) {
            ReviewRow row = review.review();
            return new CustomerVenueReview(row.id(), review.username(), row.rating(), row.comment(), row.createdAt(),
                    row.updatedAt(), review.mine());
        }
    }
}
