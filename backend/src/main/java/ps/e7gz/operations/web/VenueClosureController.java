package ps.e7gz.operations.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.VenueClosureKind;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;

/**
 * Venue Dashboard — normal Venue Closures (arch 09 §4.2, FR-VEN-30…FR-VEN-33, Spec v5 §15). Stubs of the Sprint 5
 * contract (E7-77) until E7-81. A Closure blocks every resource of the venue for its interval and cancels the
 * reservations inside it under {@code VENUE_CLOSURE}, so a save is two calls bound by a {@code previewToken}, the same
 * way as opening hours (PO decision 19 Nov 2026, E7-77, D2; FR-VEN-30 "Save & Cancel Affected Bookings"). The Owner
 * Password is needed only when a protected booking is cancelled (FR-VEN-30, FR-VEN-04). Saves cancel commitments and
 * move Booking Fee refunds, so they require the Idempotency-Key (ADR-015, as the hours commit since E7-113). The list
 * is PO decision D1 of 19 Nov 2026 (E7-77).
 */
@RestController
@RequestMapping("/api/v1/venue/closures")
@Tag(name = "venue-closures")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenueClosureController {

    @GetMapping
    @Operation(operationId = "venueListClosures",
            summary = "Closures not yet ended, earliest first (FR-VEN-30; PO decision 19 Nov 2026, E7-77)",
            description = "Every Closure of the signed-in venue whose end is after now. Implemented by E7-81.")
    public DataResponse<List<VenueClosure>> list() {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/preview")
    @Operation(operationId = "venuePreviewClosure",
            summary = "Validate a new or edited Closure and list the bookings it would cancel (FR-VEN-30…FR-VEN-32)",
            description = "Changes nothing. With closureId it previews an edit of that Closure. A Closure over an "
                    + "ACTIVE session is CLOSURE_OVERLAPS_ACTIVE_SESSION (FR-VEN-31); one that conflicts with a "
                    + "tournament is TOURNAMENT_CONFLICT_BLOCKS_SAVE (FR-VEN-32). It may overlap Maintenance "
                    + "(FR-VEN-33). Implemented by E7-81.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.OPERATIONAL_STATE_CHANGED)
    @ApiError(status = 422, codes = {ApiErrorCode.CLOSURE_OVERLAPS_ACTIVE_SESSION,
            ApiErrorCode.TOURNAMENT_CONFLICT_BLOCKS_SAVE})
    public DataResponse<VenueClosurePreview> preview(@Valid @RequestBody VenueClosurePreviewRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "venueCreateClosure",
            summary = "Save a new Closure and cancel the bookings of its preview (FR-VEN-30)",
            description = "One transaction: the Closure, one CLOSURE reservation per resource, and every affected "
                    + "booking CANCELLED / VENUE_CLOSURE with its Booking Fee refund decision; customers are notified "
                    + "after commit. The affected set is recomputed: a set different from the preview's is "
                    + "AFFECTED_SET_CHANGED_REFRESH_PREVIEW with nothing changed. The Owner Password (body) is needed "
                    + "only when a CONFIRMED online or an AWAITING booking is cancelled. Implemented by E7-81.")
    @ApiError(status = 409, codes = ApiErrorCode.AFFECTED_SET_CHANGED_REFRESH_PREVIEW)
    @ApiError(status = 422, codes = {ApiErrorCode.CLOSURE_OVERLAPS_ACTIVE_SESSION,
            ApiErrorCode.TOURNAMENT_CONFLICT_BLOCKS_SAVE, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    @ApiError(status = 429, codes = ApiErrorCode.RATE_LIMITED)
    public DataResponse<VenueClosure> create(@RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
                                             @Valid @RequestBody VenueClosureSaveRequest request) {
        throw NotImplementedYet.stub();
    }

    @PatchMapping("/{closureId}")
    @Operation(operationId = "venueEditClosure",
            summary = "Save an edited Closure and cancel the bookings of its preview (FR-VEN-30)",
            description = "The whole new Closure is sent, with the previewToken of a preview made with this closureId. "
                    + "Shrinking a Closure never restores what it cancelled. Same rules and answers as create; a "
                    + "Closure that has already ended is OPERATIONAL_STATE_CHANGED. Implemented by E7-81.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = {ApiErrorCode.AFFECTED_SET_CHANGED_REFRESH_PREVIEW,
            ApiErrorCode.OPERATIONAL_STATE_CHANGED})
    @ApiError(status = 422, codes = {ApiErrorCode.CLOSURE_OVERLAPS_ACTIVE_SESSION,
            ApiErrorCode.TOURNAMENT_CONFLICT_BLOCKS_SAVE, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    @ApiError(status = 429, codes = ApiErrorCode.RATE_LIMITED)
    public DataResponse<VenueClosure> edit(@RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
                                           @PathVariable UUID closureId,
                                           @Valid @RequestBody VenueClosureSaveRequest request) {
        throw NotImplementedYet.stub();
    }

    @DeleteMapping("/{closureId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "venueDeleteClosure", summary = "Delete a Closure that has not ended",
            description = "No Owner Password. Its CLOSURE reservations are released; bookings it cancelled stay "
                    + "cancelled. Audited. A Closure that has already ended is OPERATIONAL_STATE_CHANGED. "
                    + "Implemented by E7-81.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.OPERATIONAL_STATE_CHANGED)
    public void delete(@PathVariable UUID closureId) {
        throw NotImplementedYet.stub();
    }

    /** {@code operations.venue_closure}; the period is {@code [startAt, endAt)}. */
    @Schema(name = "VenueClosure")
    public record VenueClosure(
            @NotNull @Schema(example = "4d5e6f7a-8b9c-4d0e-9f1a-2b3c4d5e6f7a") UUID id,
            @NotNull VenueClosureKind kind,
            @NotNull @Schema(example = "2026-12-10T10:00:00Z") Instant startAt,
            @NotNull @Schema(example = "2026-12-11T00:00:00Z") Instant endAt,
            @NotNull @Schema(example = "Private event") String reason) {
    }

    /** A proposed Closure (Spec v5 §15: full-day or partial-time, the whole venue). */
    @Schema(name = "VenueClosureProposal")
    public record VenueClosureProposal(
            @NotNull VenueClosureKind kind,
            @NotNull @Schema(example = "2026-12-10T10:00:00Z") Instant startAt,
            @NotNull @Schema(description = "After startAt.", example = "2026-12-11T00:00:00Z") Instant endAt,
            @NotBlank @Size(max = 500) @Schema(example = "Private event") String reason) {
    }

    @Schema(name = "VenueClosurePreviewRequest")
    public record VenueClosurePreviewRequest(
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                    description = "The Closure being edited; absent for a new one.") UUID closureId,
            @NotNull @Valid VenueClosureProposal proposal) {
    }

    /** The complete impact preview and whether saving it will need the Owner Password. */
    @Schema(name = "VenueClosurePreview")
    public record VenueClosurePreview(
            @NotNull @Schema(description = "Bound to the exact affected set.", example = "b64:4a1d…")
            String previewToken,
            @NotNull List<VenueClosureAffectedBooking> affected,
            @NotNull Boolean ownerPasswordRequired) {
    }

    /** A reservation the Closure would cancel under VENUE_CLOSURE. */
    @Schema(name = "VenueClosureAffectedBooking")
    public record VenueClosureAffectedBooking(
            @NotNull @Schema(example = "5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9") UUID bookingId,
            @NotNull @Schema(example = "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f") UUID resourceId,
            @NotNull @Schema(example = "2026-12-10T18:00:00Z") Instant startAt,
            @NotNull @Schema(example = "2026-12-10T20:00:00Z") Instant endAt,
            @NotNull @Schema(description = "Cancelling it needs the Owner Password (FR-VEN-30).")
            Boolean protectedCancellation) {
    }

    @Schema(name = "VenueClosureSaveRequest")
    public record VenueClosureSaveRequest(
            @NotNull @Valid VenueClosureProposal proposal,
            @NotBlank String previewToken,
            @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                    description = "Required only when the preview had protected cancellations (FR-VEN-30). When "
                            + "sent, it is always checked, whatever the preview.")
            String ownerPassword) {

        @Override
        public String toString() {
            return "VenueClosureSaveRequest[proposal=" + proposal + ", <owner password redacted>]";
        }
    }
}
