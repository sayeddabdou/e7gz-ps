package ps.e7gz.booking.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingEventType;
import ps.e7gz.common.codes.BookingHistoryEntryKind;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.MoneyView;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.PageView;

/**
 * Customer bookings (arch 09 §3.3). Stubs until E7-46 (create, quote) and E7-49 (list, detail, cancel). Every write
 * here is in ADR-015's scope, so the Idempotency-Key is required. The customer DTOs are separate from the venue ones
 * (arch 09 §1.1): the venue phone is absent from the payload when the retention rule hides it (NFR-18, FR-PTS-20).
 */
@RestController
@RequestMapping("/api/v1/customer/bookings")
@Tag(name = "customer-bookings")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class CustomerBookingController {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "customerCreateBooking",
            summary = "Request a booking; PENDING until the venue answers within 10 minutes (FR-BOK-11, FR-BOK-13)",
            description = "Checks the twenty creation conditions of FR-BOK-11 in one transaction. Displayed "
                    + "availability is not a promise: a slot taken meanwhile is 409 SLOT_NO_LONGER_AVAILABLE "
                    + "(FR-AVL-11). Duration and lead time are product rules, answered with their 422 codes, not "
                    + "VALIDATION_FAILED. playMode is required for PLAYSTATION and absent otherwise. A venue with no "
                    + "price for the activity fails under FR-BOK-11 item 20 as RESOURCE_NOT_ELIGIBLE (PO decision 5 Oct "
                    + "2026). Implemented by E7-46.")
    @ApiError(status = 403, codes = {ApiErrorCode.PHONE_VERIFICATION_REQUIRED, ApiErrorCode.ACCOUNT_BANNED_FOR_THIS_ACTION,
            ApiErrorCode.ACCOUNT_DELETED})
    @ApiError(status = 409, codes = ApiErrorCode.SLOT_NO_LONGER_AVAILABLE)
    @ApiError(status = 422, codes = {ApiErrorCode.BOOKING_LEAD_TIME_TOO_SHORT, ApiErrorCode.BOOKING_DURATION_INVALID,
            ApiErrorCode.COMMITMENT_LIMIT_REACHED, ApiErrorCode.OVERLAPPING_COMMITMENT,
            ApiErrorCode.INTERVAL_NOT_WITHIN_ONE_SESSION, ApiErrorCode.ONLINE_INTAKE_WINDOW_CLOSED,
            ApiErrorCode.VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY, ApiErrorCode.RESOURCE_NOT_ELIGIBLE,
            ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE, ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE,
            ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<CustomerBookingDetail> create(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @Valid @RequestBody CustomerBookingCreateRequest request) {
        throw NotImplementedYet.stub();
    }

    @GetMapping("/quote")
    @Operation(operationId = "customerQuoteBooking",
            summary = "The price a booking would have now (FR-BOK-06, FR-BOK-07, FR-PTS-13)",
            description = "The same price the booking would snapshot if created now: hourly price × nominal minutes ÷ "
                    + "60, half-up to 2 places, plus the booking fee. Not an availability check and not a hold: the "
                    + "created booking's own amounts are authoritative. PO decision 5 Oct 2026 (E7-39). Implemented by "
                    + "E7-46.")
    @ApiError(status = 422, codes = {ApiErrorCode.BOOKING_DURATION_INVALID, ApiErrorCode.RESOURCE_NOT_ELIGIBLE})
    public DataResponse<CustomerBookingQuote> quote(
            @RequestParam UUID resourceId,
            @Parameter(description = "Required for PLAYSTATION, absent otherwise.") @RequestParam(required = false)
            PlayMode playMode,
            @RequestParam Instant startAt,
            @RequestParam Integer durationMinutes) {
        throw NotImplementedYet.stub();
    }

    @GetMapping
    @Operation(operationId = "customerListBookings",
            summary = "My bookings in one display bucket (FR-BOK-19)",
            description = "upcoming = PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED, ACTIVE · completed = "
                    + "COMPLETED · cancelled = VENUE_RESPONSE_EXPIRED, CANCELLED, REJECTED, NO_SHOW (FR-BOK-19). "
                    + "Implemented by E7-49.")
    public DataResponse<PageView<CustomerBookingSummary>> list(
            @RequestParam CustomerBookingBucket bucket,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        throw NotImplementedYet.stub();
    }

    @GetMapping("/{bookingId}")
    @Operation(operationId = "customerGetBooking",
            summary = "One of my bookings with its history (FR-BOK-20, FR-PTS-20)",
            description = "venue.phone is absent when the retention rule hides it — not null, not masked (NFR-18, "
                    + "arch 10 §5.1). Implemented by E7-49.")
    public DataResponse<CustomerBookingDetail> get(@PathVariable UUID bookingId) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{bookingId}/cancel")
    @Operation(operationId = "customerCancelBooking",
            summary = "Cancel before the start time, free and without penalty (FR-BOK-31, FR-BOK-32)",
            description = "Allowed while now < startAt, also while the venue response timer is paused (FR-BOK-32). "
                    + "At or after startAt it is DEADLINE_PASSED; a booking already terminal is BOOKING_STATE_CHANGED. "
                    + "Terminal reason CUSTOMER_CANCELLED. Implemented by E7-49.")
    @ApiError(status = 409, codes = ApiErrorCode.BOOKING_STATE_CHANGED)
    @ApiError(status = 422, codes = {ApiErrorCode.DEADLINE_PASSED, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<CustomerBookingDetail> cancel(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID bookingId) {
        throw NotImplementedYet.stub();
    }

    /** arch 09 §3.3 {@code ?bucket=upcoming|completed|cancelled}: lower case on the wire, so lower case here. */
    @Schema(name = "CustomerBookingBucket", enumAsRef = true)
    public enum CustomerBookingBucket {
        upcoming,
        completed,
        cancelled
    }

    /** F-04: the customer chooses the resource, the start and the nominal duration. */
    @Schema(name = "CustomerBookingCreateRequest")
    public record CustomerBookingCreateRequest(
            @NotNull @Schema(example = "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f") UUID resourceId,
            @Schema(nullable = true, description = "Required for PLAYSTATION, absent otherwise.") PlayMode playMode,
            @NotNull @Schema(example = "2026-10-12T18:00:00Z") Instant startAt,
            @NotNull @Schema(description = "Nominal minutes: 60–360 in steps of 30 (FR-BOK-01).", example = "120")
            Integer durationMinutes) {
    }

    /** FR-BOK-06 amounts for a would-be booking. */
    @Schema(name = "CustomerBookingQuote")
    public record CustomerBookingQuote(
            @NotNull MoneyView hourlyPrice,
            @NotNull @Schema(example = "120") Integer nominalBookedMinutes,
            @NotNull MoneyView venuePriceTotal,
            @NotNull CustomerBookingFee bookingFee,
            @NotNull MoneyView totalAmount) {
    }

    /** The booking fee; EGP 0 at launch, shown as free (FR-PTS-13). */
    @Schema(name = "CustomerBookingFee")
    public record CustomerBookingFee(
            @NotNull @Pattern(regexp = "^\\d+\\.\\d{2}$") @Schema(example = "0.00") String amount,
            @NotNull MoneyView.Currency currency,
            @NotNull @Schema(description = "True when the fee is zero (FR-PTS-13).") Boolean displayAsFree) {
    }

    /** The venue on a customer booking. {@code phone} is absent, never null, when hidden (NFR-18, FR-PTS-20). */
    @Schema(name = "CustomerBookingVenue")
    public record CustomerBookingVenue(
            @NotNull @Schema(example = "7b1f0c9e-2d3a-4e5b-8c6d-9e0f1a2b3c4d") UUID id,
            @NotNull @Schema(example = "Level Up Lounge") String name,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                    description = "E.164; absent when the retention rule hides it (arch 10 §5.1).",
                    example = "+201001234567") String phone) {
    }

    /** The booked resource as snapshotted on the booking; consoleType and playMode only for PlayStation. */
    @Schema(name = "CustomerBookingResource")
    public record CustomerBookingResource(
            @NotNull @Schema(example = "Room 3") String name,
            @NotNull Activity activity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) ConsoleType consoleType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) PlayMode playMode) {
    }

    /** A row of My Bookings (FR-BOK-19). */
    @Schema(name = "CustomerBookingSummary")
    public record CustomerBookingSummary(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID bookingId,
            @NotNull BookingStatus status,
            @NotNull CustomerBookingBucket displayBucket,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "Exactly one when REJECTED or CANCELLED (NFR-26).") BookingTerminalReason terminalReason,
            @NotNull CustomerBookingVenue venue,
            @NotNull CustomerBookingResource resource,
            @NotNull @Schema(example = "2026-10-12T18:00:00Z") Instant startAt,
            @NotNull @Schema(example = "2026-10-12T20:00:00Z") Instant endAt,
            @NotNull MoneyView totalAmount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "While PENDING: the venue's answer deadline, an instant (NFR-10).",
                    example = "2026-10-12T15:10:00Z") Instant venueResponseExpiresAt) {
    }

    /** The booking detail of arch 09 §3.3. */
    @Schema(name = "CustomerBookingDetail")
    public record CustomerBookingDetail(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID bookingId,
            @NotNull BookingStatus status,
            @NotNull CustomerBookingBucket displayBucket,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "Exactly one when REJECTED or CANCELLED (NFR-26).") BookingTerminalReason terminalReason,
            @NotNull CustomerBookingVenue venue,
            @NotNull CustomerBookingResource resource,
            @NotNull @Schema(example = "2026-10-12T18:00:00Z") Instant startAt,
            @NotNull @Schema(example = "2026-10-12T20:00:00Z") Instant endAt,
            @NotNull @Schema(example = "120") Integer nominalBookedMinutes,
            @NotNull MoneyView venuePriceTotal,
            @NotNull CustomerBookingFee bookingFee,
            @NotNull MoneyView totalAmount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "While PENDING: the venue's answer deadline, an instant — never a countdown (NFR-10).",
                    example = "2026-10-12T15:10:00Z") Instant venueResponseExpiresAt,
            @NotNull @Schema(description = "True while an emergency pauses the venue response timer (FR-BOK-32).")
            Boolean venueResponsePaused,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "Independent of status (FR-ATT-07).") AttendanceResult attendanceResult,
            @NotNull @Schema(description = "Append-only, oldest first (FR-AUD-05).") List<CustomerBookingHistoryEntry> history) {
    }

    /**
     * One {@code booking.booking_history} row: a status transition (with the terminal reason when it ended the booking)
     * or a Booking Event. Codes only; the app owns the text (ADR-021).
     */
    @Schema(name = "CustomerBookingHistoryEntry")
    public record CustomerBookingHistoryEntry(
            @NotNull @Schema(example = "2026-10-12T15:00:00Z") Instant at,
            @NotNull BookingHistoryEntryKind kind,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) BookingStatus fromStatus,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) BookingStatus toStatus,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) BookingTerminalReason terminalReason,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) BookingEventType eventType) {
    }
}
