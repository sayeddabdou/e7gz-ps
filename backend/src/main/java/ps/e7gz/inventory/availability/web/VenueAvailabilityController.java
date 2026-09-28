package ps.e7gz.inventory.availability.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.ScheduleDisplayState;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;

/** Venue Dashboard availability schedule (arch 09 §4.1, FR-VEN-37). Stub until the schedule task. */
@RestController
@RequestMapping("/api/v1/venue/availability")
@Tag(name = "venue-schedule")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenueAvailabilityController {

    @GetMapping
    @Operation(operationId = "venueGetAvailability", summary = "Resource × time schedule for one business date (FR-VEN-37)")
    public DataResponse<VenueSchedule> get(
            @Parameter(description = "Business date (arch 04 §4.3).") @RequestParam LocalDate date) {
        throw NotImplementedYet.stub();
    }

    @Schema(name = "VenueSchedule")
    public record VenueSchedule(
            @NotNull @Schema(example = "2026-10-12") LocalDate businessDate,
            @NotNull List<VenueScheduleResource> resources) {
    }

    @Schema(name = "VenueScheduleResource")
    public record VenueScheduleResource(
            @NotNull @Schema(example = "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f") UUID resourceId,
            @NotNull @Schema(example = "Room 1") String name,
            @NotNull Activity activity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) ConsoleType consoleType,
            @NotNull List<VenueScheduleSlot> slots) {
    }

    /** One contiguous span of a resource in exactly one display state (arch 09 §4.1). */
    @Schema(name = "VenueScheduleSlot")
    public record VenueScheduleSlot(
            @NotNull @Schema(example = "2026-10-12T15:00:00Z") Instant startAt,
            @NotNull @Schema(example = "2026-10-12T17:00:00Z") Instant endAt,
            @NotNull ScheduleDisplayState state,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "The booking behind the span, when there is one.") UUID bookingId) {
    }
}
