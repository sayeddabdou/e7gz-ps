package ps.e7gz.venue.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;

/**
 * Platform Admin — the Activities module (FR-ADM-01), view only (PO decision 2 Oct 2026, E7-22). Activities are the
 * closed list PLAYSTATION · BILLIARDS · PING_PONG ({@code ck_venue_activity}); there is nothing to edit platform-wide.
 * Each venue's activities are set on the venue itself ({@code POST}/{@code PATCH /admin/venues}). Stub until E7-26.
 */
@RestController
@RequestMapping("/api/v1/admin/activities")
@Tag(name = "admin-activities")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminActivityController {

    @GetMapping
    @Operation(operationId = "adminListActivities",
            summary = "The three activities and how many venues offer each (FR-ADM-01; venue.venue_activity)")
    public DataResponse<List<AdminActivityView>> list() {
        throw NotImplementedYet.stub();
    }

    /** One activity of the closed list, with the number of venues (any marketplace status) that offer it. */
    @Schema(name = "AdminActivity")
    public record AdminActivityView(
            @NotNull Activity activity,
            @NotNull @Schema(example = "12") Integer venueCount) {
    }
}
