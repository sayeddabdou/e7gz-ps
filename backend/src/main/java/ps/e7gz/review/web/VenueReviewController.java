package ps.e7gz.review.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.review.internal.CustomerReviews;
import ps.e7gz.review.internal.ReviewRow;
import ps.e7gz.review.internal.VenueReviews;

/**
 * Venue Dashboard - Reviews, view only (arch 09 section 4.1, FR-VEN-39, Spec v5 section 49.7; E7-102). The Venue DTOs
 * are their own: the author's current username, never a name, phone or id. No report or moderation route exists for
 * a Venue (arch 10 section 5.3a).
 */
@RestController
@RequestMapping("/api/v1/venue/reviews")
@Tag(name = "venue-reviews")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenueReviewController {

    private final VenueReviews reviews;
    private final ResponseMetaFactory meta;

    VenueReviewController(VenueReviews reviews, ResponseMetaFactory meta) {
        this.reviews = reviews;
        this.meta = meta;
    }

    @GetMapping
    @Operation(operationId = "venueListReviews", summary = "The Venue's rating and public Reviews (FR-VEN-39)",
            description = "Average rating, total and distribution of ACTIVE Reviews, and the ACTIVE Reviews newest "
                    + "first with cursor paging. View only.")
    @ApiError(status = 400, codes = ApiErrorCode.VALIDATION_FAILED)
    public DataResponse<VenueReviewsPage> list(@RequestParam(required = false) String cursor,
                                               @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
                                               @AuthenticationPrincipal AuthenticatedSubject venue) {
        VenueReviews.VenuePage page = reviews.list(venue.id(), cursor, limit);
        VenueReviews.Summary s = page.summary();
        return meta.data(new VenueReviewsPage(
                new VenueReviewSummary(s.averageRating(), s.reviewCount(),
                        new VenueRatingDistribution(s.one(), s.two(), s.three(), s.four(), s.five())),
                page.items().stream().map(VenueReview::of).toList(), page.nextCursor()));
    }

    @Schema(name = "VenueReviewsPage")
    public record VenueReviewsPage(
            @NotNull VenueReviewSummary summary,
            @NotNull List<VenueReview> items,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String nextCursor) {
    }

    @Schema(name = "VenueReviewSummary")
    public record VenueReviewSummary(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "4.25",
                    description = "Null when there is no ACTIVE Review.") BigDecimal averageRating,
            @NotNull @Schema(example = "12") Integer reviewCount,
            @NotNull VenueRatingDistribution distribution) {
    }

    @Schema(name = "VenueRatingDistribution", description = "ACTIVE Reviews per star rating.")
    public record VenueRatingDistribution(@NotNull Integer one, @NotNull Integer two, @NotNull Integer three,
                                          @NotNull Integer four, @NotNull Integer five) {
    }

    @Schema(name = "VenueReview")
    public record VenueReview(
            @NotNull UUID reviewId,
            @NotNull @Schema(example = "omar_k") String username,
            @NotNull Integer rating,
            @NotNull String comment,
            @NotNull Instant createdAt) {

        static VenueReview of(CustomerReviews.PublicReview review) {
            ReviewRow row = review.review();
            return new VenueReview(row.id(), review.username(), row.rating(), row.comment(), row.createdAt());
        }
    }
}
