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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.ScheduleDisplayState;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.inventory.availability.internal.VenueSchedules;

/**
 * Venue Dashboard availability schedule (arch 09 §4.1, FR-VEN-37). Implemented by E7-112: the signed-in venue only, a
 * read (no command, no Idempotency-Key).
 */
@RestController
@RequestMapping("/api/v1/venue/availability")
@Tag(name = "venue-schedule")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenueAvailabilityController {

    private final VenueSchedules schedules;
    private final ResponseMetaFactory meta;

    public VenueAvailabilityController(VenueSchedules schedules, ResponseMetaFactory meta) {
        this.schedules = schedules;
        this.meta = meta;
    }

    @GetMapping
    @Operation(operationId = "venueGetAvailability", summary = "Resource × time schedule for one business date (FR-VEN-37)",
            description = "Every non-archived resource of the signed-in venue with its spans for the business date. "
                    + "FREE only inside the Operating Session and never on a DISABLED resource; time with no slot is "
                    + "blank. Spans may overlap (e.g. an emergency CLOSURE over a booking). With no Operating Session "
                    + "on the date, closed is true and every resource has no slots.")
    public DataResponse<VenueSchedule> get(
            @AuthenticationPrincipal AuthenticatedSubject venue,
            @Parameter(description = "Business date (arch 04 §4.3). Optional: when absent, the Venue's current business "
                    + "date; the date used is returned as VenueSchedule.businessDate.")
            @RequestParam(required = false) LocalDate date) {
        return meta.data(VenueSchedule.of(schedules.of(venue.id(), date)));
    }

    @Schema(name = "VenueSchedule")
    public record VenueSchedule(
            @NotNull @Schema(example = "2026-10-12",
                    description = "The business date actually used (arch 04 §4.3).") LocalDate businessDate,
            @NotNull @Schema(description = "True when the business date has no Operating Session: every resource is "
                    + "listed with no slots.") Boolean closed,
            @NotNull List<VenueScheduleResource> resources) {

        static VenueSchedule of(VenueSchedules.Schedule schedule) {
            return new VenueSchedule(schedule.businessDate(), schedule.closed(),
                    schedule.resources().stream().map(VenueScheduleResource::of).toList());
        }
    }

    @Schema(name = "VenueScheduleResource")
    public record VenueScheduleResource(
            @NotNull @Schema(example = "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f") UUID resourceId,
            @NotNull @Schema(example = "Room 1") String name,
            @NotNull Activity activity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) ConsoleType consoleType,
            @NotNull List<VenueScheduleSlot> slots) {

        static VenueScheduleResource of(VenueSchedules.ResourceSchedule row) {
            return new VenueScheduleResource(row.resource().resourceId(), row.resource().name(),
                    row.resource().activity(), row.resource().consoleType(),
                    row.slots().stream().map(VenueScheduleSlot::of).toList());
        }
    }

    /** One contiguous span of a resource in exactly one display state (arch 09 §4.1). */
    @Schema(name = "VenueScheduleSlot")
    public record VenueScheduleSlot(
            @NotNull @Schema(example = "2026-10-12T15:00:00Z") Instant startAt,
            @NotNull @Schema(example = "2026-10-12T17:00:00Z") Instant endAt,
            @NotNull ScheduleDisplayState state,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "The booking behind the span, when there is one.") UUID bookingId) {

        static VenueScheduleSlot of(VenueSchedules.Slot slot) {
            return new VenueScheduleSlot(slot.startAt(), slot.endAt(), slot.state(), slot.bookingId());
        }
    }
}
