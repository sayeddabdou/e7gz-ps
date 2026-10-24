package ps.e7gz.booking.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.booking.internal.VenueOverviews;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.MoneyView;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.ResponseMetaFactory;

/**
 * Venue Dashboard home page data (arch 09 §4.1, FR-VEN-35), E7-115 on the Sprint 3 contract (E7-54, PO decision 6 Oct
 * 2026, E7-39 review), for the signed-in account's own venue. Today's schedule is {@code GET /venue/availability}
 * (E7-112); the quick actions are Dashboard buttons, not data.
 */
@RestController
@RequestMapping("/api/v1/venue")
@Tag(name = "venue-overview")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenueOverviewController {

    private final VenueOverviews overviews;
    private final ResponseMetaFactory meta;

    public VenueOverviewController(VenueOverviews overviews, ResponseMetaFactory meta) {
        this.overviews = overviews;
        this.meta = meta;
    }

    @GetMapping("/overview")
    @Operation(operationId = "venueGetOverview",
            summary = "Today's and this month's revenue, today's bookings and pending requests (FR-VEN-35)",
            description = "For the signed-in account's own venue. Today and this month are business dates in the "
                    + "venue's time zone (arch 04 §4.3). Revenue counts only COMPLETED bookings, online and manual "
                    + "(tournament revenue joins in Sprint 6); CONFIRMED, ACTIVE and every cancelled or rejected booking "
                    + "count nothing (PO decision 6 Oct 2026). No payment method, cash/card split or reconciliation "
                    + "field (FR-VEN-36). Every number comes from here: the Dashboard sums nothing.")
    public DataResponse<VenueOverview> get(@AuthenticationPrincipal AuthenticatedSubject venue) {
        return meta.data(VenueOverview.of(overviews.of(venue.id())));
    }

    /** FR-VEN-35: the four figures of the Overview page. */
    @Schema(name = "VenueOverview")
    public record VenueOverview(
            @NotNull @Schema(description = "COMPLETED bookings on today's business date.") MoneyView todayRevenue,
            @NotNull @Schema(description = "COMPLETED bookings from the first business date of this month to today.")
            MoneyView monthRevenue,
            @NotNull @Schema(description = "Every booking of this venue on today's business date, online and manual, "
                    + "whatever its status — cancelled, rejected and no-show included (PO decision 11 Oct 2026, "
                    + "E7-54).", example = "12")
            Integer todayBookings,
            @NotNull @Schema(description = "PENDING requests waiting for the venue's answer (FR-VEN-34).", example = "2")
            Integer pendingRequests) {

        static VenueOverview of(VenueOverviews.Overview overview) {
            return new VenueOverview(MoneyView.of(overview.todayRevenue()), MoneyView.of(overview.monthRevenue()),
                    overview.todayBookings(), overview.pendingRequests());
        }
    }
}
