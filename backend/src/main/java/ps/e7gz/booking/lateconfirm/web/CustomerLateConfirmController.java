package ps.e7gz.booking.lateconfirm.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;

/**
 * The customer's answer to a Late Confirmation (arch 09 §3.3, FRS §4.5). Stubs of the Sprint 3 contract (E7-54) until
 * E7-58. Both are in ADR-015's scope, so the Idempotency-Key is required. The response is the booking's new state;
 * the app reads the full booking from {@code GET /customer/bookings/{bookingId}} (a separate module's DTO, arch 03).
 */
@RestController
@RequestMapping("/api/v1/customer/bookings/{bookingId}/late-confirm")
@Tag(name = "customer-late-confirm")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class CustomerLateConfirmController {

    @PostMapping("/accept")
    @Operation(operationId = "customerAcceptLateConfirm",
            summary = "Accept the venue's Late Confirmation: the same booking becomes CONFIRMED (FR-LCF-13, FR-LCF-14)",
            description = "Only while AWAITING_CUSTOMER_CONFIRMATION and now < lateConfirm.expiresAt; at or after it "
                    + "is DEADLINE_PASSED, and a booking no longer AWAITING is BOOKING_STATE_CHANGED. Revalidated "
                    + "atomically (FR-LCF-14): commitment count (COMMITMENT_LIMIT_REACHED), the customer's schedule "
                    + "(LATE_CONFIRM_CUSTOMER_CONFLICT, checked only here, FR-LCF-15), the hold "
                    + "(LATE_CONFIRM_RESOURCE_CONFLICT, FR-LCF-17), ban and account (ACCOUNT_BANNED_FOR_THIS_ACTION, "
                    + "FR-ACC-17; ACCOUNT_DELETED), venue and resource operational eligibility including selling "
                    + "blocks. A failed Accept leaves the booking AWAITING until its own expiry and records the event "
                    + "(FR-LCF-16). While the timer is paused (lateConfirm.paused) Accept is refused with "
                    + "VENUE_SELLING_BLOCK_ACTIVE or RESOURCE_SELLING_BLOCK_ACTIVE and nothing changes (FR-OPS-41). "
                    + "Another customer's booking is NOT_FOUND. Implemented by E7-58.")
    @ApiError(status = 403, codes = {ApiErrorCode.ACCOUNT_BANNED_FOR_THIS_ACTION, ApiErrorCode.ACCOUNT_DELETED})
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = {ApiErrorCode.BOOKING_STATE_CHANGED, ApiErrorCode.LATE_CONFIRM_CUSTOMER_CONFLICT,
            ApiErrorCode.LATE_CONFIRM_RESOURCE_CONFLICT})
    @ApiError(status = 422, codes = {ApiErrorCode.DEADLINE_PASSED, ApiErrorCode.COMMITMENT_LIMIT_REACHED,
            ApiErrorCode.INTERVAL_NOT_WITHIN_ONE_SESSION, ApiErrorCode.AREA_INACTIVE,
            ApiErrorCode.RESOURCE_NOT_ELIGIBLE, ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE,
            ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<CustomerLateConfirmOutcome> accept(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID bookingId) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/decline")
    @Operation(operationId = "customerDeclineLateConfirm",
            summary = "Decline the venue's Late Confirmation: CANCELLED / CUSTOMER_DECLINED_LATE_CONFIRM (FR-LCF-13)",
            description = "Penalty-free; under a future non-zero fee a full refund (FRS §2.6). Works while the timer "
                    + "is paused (FR-OPS-41) and for a banned customer (FR-ACC-17). The hold is released. At or after "
                    + "lateConfirm.expiresAt it is DEADLINE_PASSED; a booking no longer AWAITING is "
                    + "BOOKING_STATE_CHANGED. Another customer's booking is NOT_FOUND. Implemented by E7-58.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.BOOKING_STATE_CHANGED)
    @ApiError(status = 422, codes = {ApiErrorCode.DEADLINE_PASSED, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<CustomerLateConfirmOutcome> decline(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID bookingId) {
        throw NotImplementedYet.stub();
    }

    /** The booking after the customer's answer: CONFIRMED, or CANCELLED with CUSTOMER_DECLINED_LATE_CONFIRM. */
    @Schema(name = "CustomerLateConfirmOutcome")
    public record CustomerLateConfirmOutcome(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID bookingId,
            @NotNull BookingStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "CUSTOMER_DECLINED_LATE_CONFIRM after a decline, otherwise null.")
            BookingTerminalReason terminalReason) {
    }
}
