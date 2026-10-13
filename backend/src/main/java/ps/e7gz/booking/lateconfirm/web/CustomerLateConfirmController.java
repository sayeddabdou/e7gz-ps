package ps.e7gz.booking.lateconfirm.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmAnswer;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmations;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.ResultAggregate;
import ps.e7gz.messaging.api.StoredResponse;

/**
 * The customer's answer to a Late Confirmation (arch 09 §3.3, FRS §4.5; E7-58, contract E7-54). Both are in ADR-015's
 * scope, so the Idempotency-Key is required. The response is the booking's new state; the app reads the full booking
 * from {@code GET /customer/bookings/{bookingId}} (a separate module's DTO, arch 03).
 */
@RestController
@RequestMapping("/api/v1/customer/bookings/{bookingId}/late-confirm")
@Tag(name = "customer-late-confirm")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class CustomerLateConfirmController {

    /** {@code technical.idempotency_record.endpoint} of the customer's answers (arch 08 section 6). */
    static final String ACCEPT_ENDPOINT = "POST /api/v1/customer/bookings/{bookingId}/late-confirm/accept";
    static final String DECLINE_ENDPOINT = "POST /api/v1/customer/bookings/{bookingId}/late-confirm/decline";
    static final String BOOKING_AGGREGATE = "BOOKING";
    static final String BOOKING_ID = "bookingId";

    private final LateConfirmations lateConfirmations;
    private final Idempotency idempotency;
    private final ResponseMetaFactory meta;

    CustomerLateConfirmController(LateConfirmations lateConfirmations, Idempotency idempotency,
                                  ResponseMetaFactory meta) {
        this.lateConfirmations = lateConfirmations;
        this.idempotency = idempotency;
        this.meta = meta;
    }

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
            @PathVariable UUID bookingId,
            @AuthenticationPrincipal AuthenticatedSubject customer) {
        IdempotentResult<CustomerLateConfirmOutcome> result = lateConfirmations.accept(customer.id(), bookingId,
                idempotency.request(customer, ACCEPT_ENDPOINT, idempotencyKey, Map.of(BOOKING_ID, bookingId), null),
                response(), CustomerLateConfirmOutcome::of);
        return meta.data(result.throwIfRefused().body());
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
            @PathVariable UUID bookingId,
            @AuthenticationPrincipal AuthenticatedSubject customer) {
        IdempotentResult<CustomerLateConfirmOutcome> result = lateConfirmations.decline(customer.id(), bookingId,
                idempotency.request(customer, DECLINE_ENDPOINT, idempotencyKey, Map.of(BOOKING_ID, bookingId), null),
                response(), CustomerLateConfirmOutcome::of);
        return meta.data(result.body());
    }

    /** 200 with the booking's new state; no contact field, so a replay needs no projection. */
    private static StoredResponse<CustomerLateConfirmOutcome> response() {
        return StoredResponse.of(CustomerLateConfirmOutcome.class, 200,
                (CustomerLateConfirmOutcome outcome) -> new ResultAggregate(BOOKING_AGGREGATE, outcome.bookingId()));
    }

    /** The booking after the customer's answer: CONFIRMED, or CANCELLED with CUSTOMER_DECLINED_LATE_CONFIRM. */
    @Schema(name = "CustomerLateConfirmOutcome")
    public record CustomerLateConfirmOutcome(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID bookingId,
            @NotNull BookingStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "CUSTOMER_DECLINED_LATE_CONFIRM after a decline, otherwise null.")
            BookingTerminalReason terminalReason) {

        static CustomerLateConfirmOutcome of(LateConfirmAnswer answer) {
            return new CustomerLateConfirmOutcome(answer.bookingId(), answer.status(), answer.terminalReason());
        }
    }
}
