package ps.e7gz.venue.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.OpeningDayMode;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.venue.internal.OpeningHoursService;

/**
 * Venue Dashboard — opening hours (arch 09 §4.2, arch 03 §5, FR-VEN-17, FR-VEN-23…FR-VEN-27). A change is two calls
 * bound by a {@code previewToken}; the commit aborts with a refreshed preview when the affected set changed (FR-VEN-26).
 * Implemented by E7-25; the commit cancels the affected bookings since E7-113 (FR-VEN-24, FR-VEN-28), so it requires the
 * {@code Idempotency-Key} (ADR-015: it cancels commitments and moves Booking Fee refunds).
 */
@RestController
@RequestMapping("/api/v1/venue/opening-hours")
@Tag(name = "venue-opening-hours")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenueOpeningHoursController {

    static final String COMMIT_ENDPOINT = "POST /api/v1/venue/opening-hours/commit";

    private final OpeningHoursService hours;
    private final ResponseMetaFactory meta;
    private final Idempotency idempotency;

    public VenueOpeningHoursController(OpeningHoursService hours, ResponseMetaFactory meta, Idempotency idempotency) {
        this.hours = hours;
        this.meta = meta;
        this.idempotency = idempotency;
    }

    @GetMapping
    @Operation(operationId = "venueGetOpeningHours", summary = "The weekly template and weekday overrides (FR-VEN-17)")
    public DataResponse<VenueOpeningHoursView> get(@AuthenticationPrincipal AuthenticatedSubject venue) {
        return meta.data(VenueOpeningHoursView.of(hours.get(venue.id())));
    }

    @PostMapping("/preview")
    @Operation(operationId = "venuePreviewOpeningHours",
            summary = "Steps 1–5: validate, block on tournament conflicts, list affected reservations (FR-VEN-23, FR-VEN-27)")
    @ApiError(status = 422, codes = ApiErrorCode.TOURNAMENT_CONFLICT_BLOCKS_SAVE)
    public DataResponse<VenueOpeningHoursPreview> preview(@AuthenticationPrincipal AuthenticatedSubject venue,
                                                          @Valid @RequestBody VenueOpeningHoursPreviewRequest request) {
        OpeningHoursService.Preview preview = hours.preview(venue.id(), request.proposal().toProposal());
        return meta.data(new VenueOpeningHoursPreview(preview.previewToken(), preview.affected().stream()
                .map(a -> new VenueAffectedReservation(a.bookingId(), a.resourceId(), a.startAt(), a.endAt(),
                        a.protectedCancellation()))
                .toList(), preview.ownerPasswordRequired()));
    }

    @PostMapping("/commit")
    @Operation(operationId = "venueCommitOpeningHours",
            summary = "Steps 6–9: commit all-or-nothing; Owner Password only when protected bookings are cancelled "
                    + "(FR-VEN-23, FR-VEN-25, FR-VEN-26)",
            description = "Saves the hours and cancels every affected booking (CANCELLED / VENUE_OPENING_HOURS_CHANGED, "
                    + "FR-VEN-24, FR-VEN-28) in one transaction: the affected set is recomputed under the venue, room "
                    + "and customer locks, and a set different from the preview's is "
                    + "AFFECTED_SET_CHANGED_REFRESH_PREVIEW with nothing changed. The Owner Password (body, never a "
                    + "header) is needed only when a CONFIRMED online or an AWAITING booking is cancelled: absent is "
                    + "OWNER_PASSWORD_REQUIRED, wrong is OWNER_PASSWORD_INVALID (audited, FR-VEN-05). Each affected "
                    + "online booking gets a full Booking Fee refund decision; the customer is notified from "
                    + "BookingCancelled. A venue account disabled meanwhile is FORBIDDEN. Implemented by E7-25 and "
                    + "E7-113.")
    @ApiError(status = 403, codes = ApiErrorCode.FORBIDDEN)
    @ApiError(status = 409, codes = ApiErrorCode.AFFECTED_SET_CHANGED_REFRESH_PREVIEW)
    @ApiError(status = 422, codes = {ApiErrorCode.TOURNAMENT_CONFLICT_BLOCKS_SAVE, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    @ApiError(status = 429, codes = ApiErrorCode.RATE_LIMITED)
    public DataResponse<VenueOpeningHoursView> commit(@RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
                                                      @AuthenticationPrincipal AuthenticatedSubject venue,
                                                      @Valid @RequestBody VenueOpeningHoursCommitRequest request) {
        IdempotentResult<VenueOpeningHoursView> result = hours.commit(venue.id(), request.proposal().toProposal(),
                request.previewToken(), request.ownerPassword(),
                idempotency.request(venue, COMMIT_ENDPOINT, idempotencyKey, Map.of(), request),
                StoredResponse.of(VenueOpeningHoursView.class, 200), VenueOpeningHoursView::of);
        return meta.data(result.body());
    }

    /** {@code venue.venue_opening_template} + {@code venue_opening_day}; times are venue local time. */
    @Schema(name = "VenueOpeningHours")
    public record VenueOpeningHoursView(
            @NotNull @Schema(example = "Africa/Cairo") String timezone,
            @NotNull @Schema(example = "12:00:00") LocalTime defaultOpen,
            @NotNull @Schema(example = "02:00:00") LocalTime defaultClose,
            @NotNull Boolean defaultFullDay,
            @NotNull List<VenueOpeningDay> days,
            @NotNull @Schema(example = "3") Long version) {

        static VenueOpeningHoursView of(OpeningHoursService.View view) {
            return new VenueOpeningHoursView(view.timezone(), view.defaultOpen(), view.defaultClose(),
                    view.defaultFullDay(), view.days().stream()
                    .map(d -> new VenueOpeningDay(d.weekday(), d.mode(), d.customOpen(), d.customClose())).toList(),
                    view.version());
        }
    }

    /** A weekday override: DEFAULT, CUSTOM (with both times) or CLOSED ({@code ck_opening_day_custom_times}). */
    @Schema(name = "VenueOpeningDay")
    public record VenueOpeningDay(
            @NotNull @Min(0) @Max(6) @Schema(description = "venue.venue_opening_day.weekday: 0 = Monday … 6 = Sunday.", example = "4")
            Integer weekday,
            @NotNull OpeningDayMode mode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "14:00:00") LocalTime customOpen,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "03:00:00") LocalTime customClose) {
    }

    /** The proposed template (FR-VEN-17). */
    @Schema(name = "VenueOpeningHoursProposal")
    public record VenueOpeningHoursProposal(
            @NotNull @Schema(example = "12:00:00") LocalTime defaultOpen,
            @NotNull @Schema(example = "02:00:00") LocalTime defaultClose,
            @NotNull Boolean defaultFullDay,
            @NotNull List<@Valid VenueOpeningDay> days) {

        OpeningHoursService.Proposal toProposal() {
            return new OpeningHoursService.Proposal(defaultOpen, defaultClose, defaultFullDay, days.stream()
                    .map(d -> new OpeningHoursService.Day(d.weekday(), d.mode(), d.customOpen(), d.customClose()))
                    .toList());
        }
    }

    @Schema(name = "VenueOpeningHoursPreviewRequest")
    public record VenueOpeningHoursPreviewRequest(@NotNull @Valid VenueOpeningHoursProposal proposal) {
    }

    /** The complete impact preview (FR-VEN-23 step 5) and whether committing it will need the Owner Password (step 6). */
    @Schema(name = "VenueOpeningHoursPreview")
    public record VenueOpeningHoursPreview(
            @NotNull @Schema(description = "Bound to the exact affected set (arch 03 §5).", example = "b64:9f2c…")
            String previewToken,
            @NotNull List<VenueAffectedReservation> affected,
            @NotNull Boolean ownerPasswordRequired) {
    }

    /** A future reservation the change would cancel (FR-VEN-24). */
    @Schema(name = "VenueAffectedReservation")
    public record VenueAffectedReservation(
            @NotNull @Schema(example = "5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9") UUID bookingId,
            @NotNull @Schema(example = "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f") UUID resourceId,
            @NotNull @Schema(example = "2026-10-15T22:00:00Z") Instant startAt,
            @NotNull @Schema(example = "2026-10-16T00:00:00Z") Instant endAt,
            @NotNull @Schema(description = "Cancelling it needs the Owner Password (FR-VEN-25).") Boolean protectedCancellation) {
    }

    @Schema(name = "VenueOpeningHoursCommitRequest")
    public record VenueOpeningHoursCommitRequest(
            @NotNull @Valid VenueOpeningHoursProposal proposal,
            @NotBlank String previewToken,
            @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                    description = "Required only when the preview had protected cancellations (FR-VEN-25).")
            String ownerPassword) {
    }
}
