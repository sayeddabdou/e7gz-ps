package ps.e7gz.booking.lateconfirm.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;

/**
 * The venue sends a Late Confirmation (arch 09 §4.1, FRS §4.5). A stub of the Sprint 3 contract (E7-54) until E7-58.
 * No Owner Password; in ADR-015's scope, so the Idempotency-Key is required.
 *
 * <p>The venue is never told why a customer is ineligible (FR-LCF-05, NFR-19, arch 10 §5.2): the only customer
 * ineligibility code this route declares is {@code CUSTOMER_NOT_ELIGIBLE_FOR_LATE_CONFIRM}, with no details and no
 * field. The other codes are about the booking, the venue or the room.
 */
@RestController
@RequestMapping("/api/v1/venue/bookings/{bookingId}/late-confirm")
@Tag(name = "venue-late-confirm")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenueLateConfirmController {

    @PostMapping
    @Operation(operationId = "venueSendLateConfirm",
            summary = "Send a Late Confirmation for a missed request: AWAITING_CUSTOMER_CONFIRMATION (FR-LCF-06, "
                    + "FR-VEN-04)",
            description = "Only for VENUE_RESPONSE_EXPIRED (otherwise BOOKING_STATE_CHANGED) and only while now < "
                    + "startAt (otherwise DEADLINE_PASSED). One atomic transaction: commitment count, limit, room, "
                    + "temporary hold, status (FR-LCF-06). The customer's window ends at MIN(now + 5 min, startAt) "
                    + "(FR-LCF-09); not restricted by the intake window (FR-LCF-07). A customer who is not eligible "
                    + "(4 commitments, banned, deleted) is CUSTOMER_NOT_ELIGIBLE_FOR_LATE_CONFIRM — nothing else is "
                    + "said, no hold is created, the booking stays VENUE_RESPONSE_EXPIRED (FR-LCF-04, FR-LCF-05). "
                    + "Venue eligibility (FR-LCF-02; PO decision 11 Oct 2026, E7-54): the venue not inside an operating "
                    + "session now, or the original interval no longer within the opening hours, is "
                    + "INTERVAL_NOT_WITHIN_ONE_SESSION; an inactive area is AREA_INACTIVE; a venue selling block is "
                    + "VENUE_SELLING_BLOCK_ACTIVE. Room eligibility (FR-LCF-03): RESOURCE_NOT_ELIGIBLE, "
                    + "RESOURCE_SELLING_BLOCK_ACTIVE, or LATE_CONFIRM_RESOURCE_CONFLICT when the interval is taken. "
                    + "A venue account that is not ENABLED is FORBIDDEN. A booking of another venue is NOT_FOUND. "
                    + "Implemented by E7-58.")
    @ApiError(status = 403, codes = ApiErrorCode.FORBIDDEN)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = {ApiErrorCode.BOOKING_STATE_CHANGED, ApiErrorCode.LATE_CONFIRM_RESOURCE_CONFLICT})
    @ApiError(status = 422, codes = {ApiErrorCode.CUSTOMER_NOT_ELIGIBLE_FOR_LATE_CONFIRM, ApiErrorCode.DEADLINE_PASSED,
            ApiErrorCode.INTERVAL_NOT_WITHIN_ONE_SESSION, ApiErrorCode.AREA_INACTIVE,
            ApiErrorCode.RESOURCE_NOT_ELIGIBLE, ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE,
            ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<VenueLateConfirmation> send(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID bookingId) {
        throw NotImplementedYet.stub();
    }

    /** The booking after the venue sent the Late Confirmation (same Booking ID, FR-LCF-08). */
    @Schema(name = "VenueLateConfirmation")
    public record VenueLateConfirmation(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID bookingId,
            @NotNull @Schema(description = "AWAITING_CUSTOMER_CONFIRMATION.") BookingStatus status,
            @NotNull @Schema(description = "MIN(initiation + 5 min, startAt): an instant, never a countdown "
                    + "(FR-LCF-09, NFR-10).", example = "2026-10-12T17:35:00Z") Instant lateConfirmExpiresAt) {
    }
}
