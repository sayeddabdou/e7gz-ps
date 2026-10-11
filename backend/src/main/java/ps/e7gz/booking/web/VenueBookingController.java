package ps.e7gz.booking.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.booking.internal.BookingHistoryEntry;
import ps.e7gz.booking.internal.ManualBookingRequest;
import ps.e7gz.booking.internal.ManualBookings;
import ps.e7gz.booking.internal.VenueBookingFilter;
import ps.e7gz.booking.internal.VenueBookingRow;
import ps.e7gz.booking.internal.VenueBookings;
import ps.e7gz.booking.internal.VenueResponses;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingEventType;
import ps.e7gz.common.codes.BookingHistoryEntryKind;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.MoneyView;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.PageView;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.ResultAggregate;
import ps.e7gz.messaging.api.StoredResponse;

/**
 * Venue Dashboard — booking requests and bookings (arch 09 §4.1). The request lists, accept, reject and reject after
 * expiry are E7-47's, manual booking and its cancel E7-48's, the bookings list and detail E7-49's; cancel-confirmed is a
 * stub of the Sprint 3 contract (E7-54) until E7-114. Every route acts for the signed-in account's own venue, never one
 * named by the request. Only cancel-confirmed needs the Owner Password (FR-VEN-03); every write is in ADR-015's scope,
 * so the Idempotency-Key is required. The venue DTOs are separate from the customer ones (arch 09 §1.1): they carry
 * nothing about the customer's other commitments (NFR-19), and the customer phone is absent when the retention rule
 * hides it (NFR-18, arch 10 §5.1; PO decision 11 Oct 2026, E7-49, D1).
 */
@RestController
@RequestMapping("/api/v1/venue")
@Tag(name = "venue-bookings")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenueBookingController {

    /** {@code technical.idempotency_record.endpoint} of the venue's answers (arch 08 section 6). */
    static final String ACCEPT_ENDPOINT = "POST /api/v1/venue/bookings/{bookingId}/accept";
    static final String REJECT_ENDPOINT = "POST /api/v1/venue/bookings/{bookingId}/reject";
    static final String REJECT_AFTER_EXPIRY_ENDPOINT = "POST /api/v1/venue/bookings/{bookingId}/reject-after-expiry";
    static final String CREATE_MANUAL_ENDPOINT = "POST /api/v1/venue/bookings";
    static final String CANCEL_MANUAL_ENDPOINT = "POST /api/v1/venue/bookings/{bookingId}/cancel-manual";
    static final String BOOKING_AGGREGATE = "BOOKING";
    static final String BOOKING_ID = "bookingId";

    private final VenueBookings bookings;
    private final VenueResponses responses;
    private final ManualBookings manualBookings;
    private final Idempotency idempotency;
    private final ResponseMetaFactory meta;

    public VenueBookingController(VenueBookings bookings, VenueResponses responses, ManualBookings manualBookings,
                                  Idempotency idempotency, ResponseMetaFactory meta) {
        this.bookings = bookings;
        this.responses = responses;
        this.manualBookings = manualBookings;
        this.idempotency = idempotency;
        this.meta = meta;
    }

    @GetMapping("/requests/pending")
    @Operation(operationId = "venueListPendingRequests",
            summary = "Online requests waiting for an answer (FR-VEN-34, FR-BOK-13)",
            description = "PENDING bookings. Each carries venueResponseExpiresAt, an instant, for the countdown "
                    + "(NFR-10). Implemented by E7-47.")
    public DataResponse<List<VenueBooking>> listPendingRequests(@AuthenticationPrincipal AuthenticatedSubject venue) {
        return meta.data(bookings.pending(venue.id()).stream().map(VenueBooking::of).toList());
    }

    @GetMapping("/requests/missed")
    @Operation(operationId = "venueListMissedRequests",
            summary = "Requests the venue did not answer in time, still before their start (FR-VEN-34, FR-BOK-20)",
            description = "VENUE_RESPONSE_EXPIRED bookings whose startAt has not passed: the venue may still reject "
                    + "them (FR-BOK-21) or, from Sprint 3, send a Late Confirm. Implemented by E7-47.")
    public DataResponse<List<VenueBooking>> listMissedRequests(@AuthenticationPrincipal AuthenticatedSubject venue) {
        return meta.data(bookings.missed(venue.id()).stream().map(VenueBooking::of).toList());
    }

    @PostMapping("/bookings/{bookingId}/accept")
    @Operation(operationId = "venueAcceptBooking",
            summary = "Accept a PENDING request: it becomes CONFIRMED (FR-BOK-14, FR-BOK-15)",
            description = "Revalidated atomically (FR-BOK-14); marketplace ACTIVE is not required (FR-BOK-15). At or "
                    + "after venueResponseExpiresAt it is DEADLINE_PASSED (expiry wins at the boundary). When an "
                    + "emergency or owner review stops the confirmation, the booking is cancelled under that cause "
                    + "(FR-BOK-16) and the venue gets the matching 422 (VENUE_SELLING_BLOCK_ACTIVE, "
                    + "RESOURCE_SELLING_BLOCK_ACTIVE or RESOURCE_NOT_ELIGIBLE; PO decision 5 Oct 2026). A booking no "
                    + "longer PENDING is BOOKING_STATE_CHANGED. No Owner Password. Order (PO decision D7, 10 Oct 2026): "
                    + "not PENDING, then a paused response timer, then the deadline, then the revalidation. While an "
                    + "emergency pauses the response timer the Accept is refused and nothing changes: "
                    + "VENUE_SELLING_BLOCK_ACTIVE (venue-level pause) or RESOURCE_SELLING_BLOCK_ACTIVE (resource-level "
                    + "pause); nothing is stored, so a retry with the same key is evaluated afresh (D8). So the same "
                    + "422 code can mean \"still PENDING (paused)\" or \"cancelled (FR-BOK-16)\": the dashboard must "
                    + "reload the booking after any 422 on Accept. A customer who is not eligible now (banned, deleted "
                    + "or restricted) is CUSTOMER_NOT_ELIGIBLE, the booking unchanged and the reason never told (D9). A "
                    + "venue account that is not ENABLED is FORBIDDEN. A booking of another venue is NOT_FOUND. A "
                    + "replay of an Accept that cancelled answers the same 422. Implemented by E7-47.")
    @ApiError(status = 403, codes = ApiErrorCode.FORBIDDEN)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.BOOKING_STATE_CHANGED)
    @ApiError(status = 422, codes = {ApiErrorCode.DEADLINE_PASSED, ApiErrorCode.RESOURCE_NOT_ELIGIBLE,
            ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE, ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE,
            ApiErrorCode.CUSTOMER_NOT_ELIGIBLE, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<VenueBooking> accept(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID bookingId,
            @AuthenticationPrincipal AuthenticatedSubject venue) {
        IdempotentResult<VenueBooking> result = responses.accept(venue.id(), bookingId,
                idempotency.request(venue, ACCEPT_ENDPOINT, idempotencyKey, Map.of(BOOKING_ID, bookingId), null),
                response(), VenueBooking::of);
        return meta.data(result.throwIfRefused().body());
    }

    @PostMapping("/bookings/{bookingId}/reject")
    @Operation(operationId = "venueRejectBooking",
            summary = "Reject a PENDING request with a reason: REJECTED / VENUE_REJECTED (FR-BOK-17)",
            description = "The venue's reason text is mandatory and kept apart from the reason code "
                    + "(ck_booking_reject_reason_text). The reservation is released at once. A booking no longer "
                    + "PENDING is BOOKING_STATE_CHANGED. At or after venueResponseExpiresAt it is DEADLINE_PASSED. While "
                    + "an emergency pauses the response timer the venue may still reject (only Accept is disabled, "
                    + "FR-OPS-34; PO decision 10 Oct 2026): the pause ends with the rejection and there is no deadline "
                    + "check. A booking of another venue is NOT_FOUND. No Owner Password. Implemented by E7-47.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.BOOKING_STATE_CHANGED)
    @ApiError(status = 422, codes = {ApiErrorCode.DEADLINE_PASSED, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<VenueBooking> reject(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID bookingId,
            @Valid @RequestBody VenueBookingRejectRequest request,
            @AuthenticationPrincipal AuthenticatedSubject venue) {
        IdempotentResult<VenueBooking> result = responses.reject(venue.id(), bookingId, request.reasonText(),
                idempotency.request(venue, REJECT_ENDPOINT, idempotencyKey, Map.of(BOOKING_ID, bookingId), request),
                response(), VenueBooking::of);
        return meta.data(result.body());
    }

    @PostMapping("/bookings/{bookingId}/reject-after-expiry")
    @Operation(operationId = "venueRejectBookingAfterExpiry",
            summary = "Reject a missed request before its start: REJECTED / REJECTED_AFTER_EXPIRY (FR-BOK-21)",
            description = "Only for VENUE_RESPONSE_EXPIRED and only while now < startAt; at or after startAt it is "
                    + "DEADLINE_PASSED; a booking no longer VENUE_RESPONSE_EXPIRED is BOOKING_STATE_CHANGED. No "
                    + "reservation to release. A booking of another venue is NOT_FOUND. No Owner Password. Implemented "
                    + "by E7-47.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.BOOKING_STATE_CHANGED)
    @ApiError(status = 422, codes = {ApiErrorCode.DEADLINE_PASSED, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<VenueBooking> rejectAfterExpiry(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID bookingId,
            @AuthenticationPrincipal AuthenticatedSubject venue) {
        IdempotentResult<VenueBooking> result = responses.rejectAfterExpiry(venue.id(), bookingId,
                idempotency.request(venue, REJECT_AFTER_EXPIRY_ENDPOINT, idempotencyKey,
                        Map.of(BOOKING_ID, bookingId), null),
                response(), VenueBooking::of);
        return meta.data(result.body());
    }

    /** 200 with the booking (see {@link #response(int)}). */
    private StoredResponse<VenueBooking> response() {
        return response(200);
    }

    /**
     * The booking with {@code status}; a replay leaves the customer phone out once the retention rule hides it (arch 10
     * §5.1), re-applying the same rule as the normal read path. A manual booking has no customer: its walk-in phone is
     * what the venue typed and is exempt (FR-DIS-09), so its replay is unchanged.
     */
    private StoredResponse<VenueBooking> response(int status) {
        return StoredResponse.of(VenueBooking.class, status,
                        (VenueBooking booking) -> new ResultAggregate(BOOKING_AGGREGATE, booking.bookingId()))
                .replayedThrough((context, stored) -> bookings.customerPhoneVisible(context, stored.bookingId())
                        ? stored : stored.withoutCustomerPhone());
    }

    @PostMapping("/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "venueCreateManualBooking",
            summary = "Walk-in / manual booking: CONFIRMED for a future start, ACTIVE when it starts now (FR-MAN-01…FR-MAN-04)",
            description = "Leave startAt out for a walk-in starting now (startAt = server time, FR-MAN-04). Opening "
                    + "hours and the Area do not gate it (FR-MAN-03); no back-dating (FR-MAN-05); missing pricing is "
                    + "MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING and creates nothing (FR-MAN-10). Always has a "
                    + "businessDate (FR-MAN-09): the operating day when the whole interval fits one published session, "
                    + "otherwise the Africa/Cairo date of startAt. customerName and customerPhone are trimmed and a blank "
                    + "one is absent; customerPhone is normalised to E.164 like the venue phone (VALIDATION_FAILED "
                    + "otherwise). An ONBOARDING venue is FORBIDDEN (FR-MAN-02; PO decision 5 Oct 2026); a venue account "
                    + "disabled meanwhile is FORBIDDEN too (the token itself stops working: 401). A closure, emergency "
                    + "or maintenance in the interval is SLOT_NO_LONGER_AVAILABLE, as is another booking on it; a "
                    + "selling block now is VENUE_SELLING_BLOCK_ACTIVE / RESOURCE_SELLING_BLOCK_ACTIVE (PO decision 10 "
                    + "Oct 2026, E7-48). A Resource of another venue is NOT_FOUND. No Owner Password. Implemented by "
                    + "E7-48.")
    @ApiError(status = 403, codes = ApiErrorCode.FORBIDDEN)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.SLOT_NO_LONGER_AVAILABLE)
    @ApiError(status = 422, codes = {ApiErrorCode.MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING,
            ApiErrorCode.MANUAL_BOOKING_BACKDATING_NOT_ALLOWED, ApiErrorCode.BOOKING_DURATION_INVALID,
            ApiErrorCode.RESOURCE_NOT_ELIGIBLE, ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE,
            ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public ResponseEntity<DataResponse<VenueBooking>> createManual(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @Valid @RequestBody VenueManualBookingRequest request,
            @AuthenticationPrincipal AuthenticatedSubject venue) {
        // PO decision 10 Oct 2026, E7-48 (D10): trimmed, blank -> absent, phone in E.164 (400 before any record)
        ManualBookingRequest manual = ManualBookingRequest.of(request.resourceId(), request.playMode(),
                request.startAt(), request.durationMinutes(), request.customerName(), request.customerPhone());
        IdempotentResult<VenueBooking> result = manualBookings.create(venue.id(), manual,
                idempotency.request(venue, CREATE_MANUAL_ENDPOINT, idempotencyKey, Map.of(), request),
                response(201), VenueBooking::of);
        return ResponseEntity.status(result.status()).body(meta.data(result.body()));
    }

    @PostMapping("/bookings/{bookingId}/cancel-manual")
    @Operation(operationId = "venueCancelManualBooking",
            summary = "Cancel a CONFIRMED or ACTIVE manual booking: CANCELLED / VENUE_CANCELLED_MANUAL_BOOKING (FR-MAN-08, PCR-007)",
            description = "Only for source MANUAL, from CONFIRMED (before or after startAt) or ACTIVE (FR-MAN-08 as "
                    + "amended by PCR-007; PO decision 10 Oct 2026, E7-48). The room is free from the cancel instant. "
                    + "An online booking, or one already cancelled or completed, is BOOKING_STATE_CHANGED. A booking of "
                    + "another venue is NOT_FOUND. A venue account disabled meanwhile is FORBIDDEN (the token itself "
                    + "stops working: 401). No Owner Password (FR-MAN-08). Implemented by E7-48.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.BOOKING_STATE_CHANGED)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<VenueBooking> cancelManual(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID bookingId,
            @AuthenticationPrincipal AuthenticatedSubject venue) {
        IdempotentResult<VenueBooking> result = manualBookings.cancel(venue.id(), bookingId,
                idempotency.request(venue, CANCEL_MANUAL_ENDPOINT, idempotencyKey, Map.of(BOOKING_ID, bookingId),
                        null),
                response(), VenueBooking::of);
        return meta.data(result.body());
    }

    @PostMapping("/bookings/{bookingId}/cancel-confirmed")
    @Operation(operationId = "venueCancelConfirmedBooking",
            summary = "Cancel a CONFIRMED online booking with the Owner PIN: CANCELLED / VENUE_CANCELLED_CONFIRMED_BOOKING "
                    + "(FR-VEN-03)",
            description = "Owner Password in the body, never a header (ADR-011, arch 09 §4.3): absent or blank is "
                    + "OWNER_PASSWORD_REQUIRED, wrong is OWNER_PASSWORD_INVALID; every attempt is audited (FR-VEN-05). "
                    + "The reservation and the customer commitment are released; fee treatment FULL (FRS §2.6, "
                    + "FR-PTS-15); the customer is notified. No time limit: allowed while the booking is CONFIRMED, "
                    + "even after startAt if the start job has not run yet (PO decision 6 Oct 2026). A booking that is "
                    + "not a CONFIRMED online booking (already cancelled, ACTIVE, PENDING, or a manual booking, which "
                    + "uses cancel-manual, FR-MAN-08) is BOOKING_STATE_CHANGED. A booking of another venue is "
                    + "NOT_FOUND. Implemented by E7-114.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.BOOKING_STATE_CHANGED)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public DataResponse<VenueBooking> cancelConfirmed(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID bookingId,
            @RequestBody VenueBookingCancelConfirmedRequest request) {
        throw NotImplementedYet.stub();
    }

    @GetMapping("/bookings")
    @Operation(operationId = "venueListBookings",
            summary = "Booking history with filters (FR-VEN-38)",
            description = "Every booking of this venue, online and manual, newest first (startAt descending), "
                    + "cursor-paged. from and to are business dates, inclusive (arch 04 §4.3); without them, all "
                    + "dates (PO decision 11 Oct 2026, E7-49, D4); from after to is VALIDATION_FAILED. q matches the "
                    + "exact booking ID, the customer's exact current username (trimmed, case-insensitive; never part of "
                    + "one), and a part of the walk-in name or phone or of the resource name, case-insensitively - never "
                    + "the customer's phone (D3, amended 11 Oct 2026). The customer phone is absent when "
                    + "the retention rule hides it (arch 10 §5.1). Implemented by E7-49.")
    public DataResponse<PageView<VenueBooking>> list(
            @Parameter(description = "First business date, inclusive.") @RequestParam(required = false) LocalDate from,
            @Parameter(description = "Last business date, inclusive.") @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Activity activity,
            @RequestParam(required = false) BookingSource source,
            @RequestParam(required = false) UUID resourceId,
            @RequestParam(required = false) BookingStatus status,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            @AuthenticationPrincipal AuthenticatedSubject venue) {
        VenueBookings.Page page = bookings.list(venue.id(),
                new VenueBookingFilter(from, to, activity, source, resourceId, status, q), cursor, limit);
        return meta.data(new PageView<>(page.items().stream().map(VenueBooking::of).toList(), page.nextCursor()));
    }

    @GetMapping("/bookings/{bookingId}")
    @Operation(operationId = "venueGetBooking",
            summary = "One booking of this venue with its history (FR-VEN-34)",
            description = "The customer phone is absent when the retention rule hides it (arch 10 §5.1). The history "
                    + "never shows LATE_CONFIRM_FAILED_BOOKING_LIMIT or LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT: the "
                    + "venue is never told why a customer is ineligible (NFR-19, arch 10 §5.2). A booking of another "
                    + "venue is NOT_FOUND. Implemented by E7-49.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    public DataResponse<VenueBookingDetail> get(@PathVariable UUID bookingId,
                                                @AuthenticationPrincipal AuthenticatedSubject venue) {
        VenueBookings.Detail detail = bookings.detail(venue.id(), bookingId);
        return meta.data(new VenueBookingDetail(VenueBooking.of(detail.booking()),
                detail.history().stream().map(VenueBookingHistoryEntry::of).toList()));
    }

    /** FR-BOK-17: mandatory free text, separate from the reason code; max 200 characters (PO, 5 Oct 2026). */
    @Schema(name = "VenueBookingRejectRequest")
    public record VenueBookingRejectRequest(
            @NotBlank @Size(min = 1, max = 200) @Pattern(regexp = ".*\\S.*", flags = Pattern.Flag.DOTALL)
            @Schema(example = "Room under maintenance") String reasonText) {
    }

    /** FR-VEN-03, ADR-011: the Owner PIN travels in the body of the protected command. */
    @Schema(name = "VenueBookingCancelConfirmedRequest")
    public record VenueBookingCancelConfirmedRequest(
            @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "4-digit Owner PIN; absent or blank is 423 OWNER_PASSWORD_REQUIRED.")
            String ownerPassword) {

        @Override
        public String toString() {
            return "VenueBookingCancelConfirmedRequest[<redacted>]";
        }
    }

    /** F-16: a NOW or FUTURE internal booking, optionally with the customer's name and phone (FR-MAN-01). */
    @Schema(name = "VenueManualBookingRequest")
    public record VenueManualBookingRequest(
            @NotNull @Schema(example = "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f") UUID resourceId,
            @Schema(nullable = true, description = "Required for PLAYSTATION, absent otherwise.") PlayMode playMode,
            @Schema(nullable = true, description = "Absent for a walk-in starting now (FR-MAN-04).",
                    example = "2026-10-12T18:00:00Z") Instant startAt,
            @NotNull @Schema(description = "Nominal minutes: 60–360 in steps of 30 (FR-MAN-02).", example = "120")
            Integer durationMinutes,
            @Size(max = 200) @Schema(nullable = true, example = "Omar") String customerName,
            @Size(max = 40) @Schema(nullable = true, example = "01001234567") String customerPhone) {
    }

    /** The resource as snapshotted on the booking. */
    @Schema(name = "VenueBookingResource")
    public record VenueBookingResource(
            @NotNull @Schema(example = "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f") UUID id,
            @NotNull @Schema(example = "Room 3") String name,
            @NotNull Activity activity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) ConsoleType consoleType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) PlayMode playMode) {
    }

    /** The customer of an online booking. {@code phone} is absent, never null, when hidden (arch 10 §5.1). */
    @Schema(name = "VenueBookingCustomer")
    public record VenueBookingCustomer(
            @NotNull @Schema(example = "omar_k") String username,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                    description = "E.164; absent when the retention rule hides it (arch 10 §5.1).",
                    example = "+201001234567") String phone) {
    }

    /** What the venue typed on a manual booking; exempt from the retention rule (FR-DIS-09). */
    @Schema(name = "VenueBookingWalkIn")
    public record VenueBookingWalkIn(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "Omar") String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "+201001234567") String phone) {
    }

    /** A booking as the venue sees it: request lists, bookings list and every action's response. */
    @Schema(name = "VenueBooking")
    public record VenueBooking(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID bookingId,
            @NotNull BookingSource source,
            @NotNull BookingStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "Exactly one when REJECTED or CANCELLED (NFR-26).") BookingTerminalReason terminalReason,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "The venue's own reason, with VENUE_REJECTED only (FR-BOK-17).") String rejectReasonText,
            @NotNull VenueBookingResource resource,
            @NotNull @Schema(example = "2026-10-12T18:00:00Z") Instant startAt,
            @NotNull @Schema(example = "2026-10-12T20:00:00Z") Instant endAt,
            @NotNull @Schema(description = "The revenue day (arch 04 §4.3, FR-MAN-09).", example = "2026-10-12")
            LocalDate businessDate,
            @NotNull @Schema(example = "120") Integer nominalBookedMinutes,
            @NotNull MoneyView venuePriceTotal,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "While PENDING: the answer deadline, an instant — never a countdown (NFR-10).",
                    example = "2026-10-12T15:10:00Z") Instant venueResponseExpiresAt,
            @NotNull @Schema(description = "True while an emergency pauses the response timer.") Boolean venueResponsePaused,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "While AWAITING_CUSTOMER_CONFIRMATION: the customer's Late Confirm deadline, "
                            + "MIN(initiation + 5 min, startAt), an instant — never a countdown (FR-LCF-09, NFR-10).",
                    example = "2026-10-12T17:35:00Z") Instant lateConfirmExpiresAt,
            @NotNull @Schema(description = "True while an emergency or selling block pauses the Late Confirm timer "
                    + "(FR-OPS-41).") Boolean lateConfirmPaused,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "ONLINE bookings only.") VenueBookingCustomer customer,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "MANUAL bookings only.") VenueBookingWalkIn walkIn,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) AttendanceResult attendanceResult,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "ONLINE bookings only: the check-in and late windows as instants and the evidence "
                            + "recorded. A MANUAL booking has no attendance actions (FR-MAN-04).")
            VenueBookingAttendance attendance,
            @NotNull @Schema(example = "2026-10-12T15:00:00Z") Instant createdAt) {

        /**
         * The venue's view of a booking row. {@code customer.phone} is already decided by the retention rule (absent
         * when hidden). {@code attendanceResult} stays null here: no booking E7-47 answers with has started, and a
         * manual booking gets no attendance record at creation or cancellation - its NOT_APPLICABLE result is E7-61's
         * (FR-ATT-07, FR-MAN-06; PO decision 10 Oct 2026, E7-48, D9; the bookings list and detail are E7-49's).
         * {@code lateConfirmExpiresAt} / {@code lateConfirmPaused} are E7-58's and {@code attendance} E7-59's (Sprint 3
         * contract, E7-54): no booking these routes answer with is AWAITING_CUSTOMER_CONFIRMATION or has started.
         */
        public static VenueBooking of(VenueBookingRow row) {
            VenueBookingCustomer customer = row.source() == BookingSource.ONLINE
                    ? new VenueBookingCustomer(row.customerUsername(), row.customerPhone()) : null;
            VenueBookingWalkIn walkIn = row.source() == BookingSource.MANUAL
                    ? new VenueBookingWalkIn(row.walkInName(), row.walkInPhone()) : null;
            return new VenueBooking(row.bookingId(), row.source(), row.status(), row.terminalReason(),
                    row.rejectReasonText(), new VenueBookingResource(row.resourceId(), row.resourceName(),
                            row.activity(), row.consoleType(), row.playMode()),
                    row.startAt(), row.endAt(), row.businessDate(), row.nominalBookedMinutes(),
                    MoneyView.of(row.venuePriceTotal()), row.venueResponseExpiresAt(), row.venueResponsePaused(),
                    null, false, customer, walkIn, null, null, row.createdAt());
        }

        /** The same booking with the customer phone absent (arch 10 §5.1): only a member left out, nothing changed. */
        VenueBooking withoutCustomerPhone() {
            return customer == null ? this : new VenueBooking(bookingId, source, status, terminalReason,
                    rejectReasonText, resource, startAt, endAt, businessDate, nominalBookedMinutes, venuePriceTotal,
                    venueResponseExpiresAt, venueResponsePaused, lateConfirmExpiresAt, lateConfirmPaused,
                    new VenueBookingCustomer(customer.username(), null), walkIn, attendanceResult, attendance, createdAt);
        }
    }

    /**
     * The attendance windows of an online booking as instants (arch 07 §7.1, FRS §2.5) and the evidence recorded, so
     * the Dashboard enables each action from the API and the server clock, never its own rule (NFR-10). Each window
     * is half-open: open at {@code opensAt}, closed at {@code closesAt}. Evidence reversed by a Platform Admin is
     * absent (FR-ATT-11).
     */
    @Schema(name = "VenueBookingAttendance")
    public record VenueBookingAttendance(
            @NotNull @Schema(description = "Check-in: [startAt, startAt + 15 min) (FR-ATT-02).")
            VenueBookingTimeWindow checkInWindow,
            @NotNull @Schema(description = "Mark Present — Late and No-show: [startAt + 15 min, endAt) (FR-ATT-03, "
                    + "FR-ATT-04).") VenueBookingTimeWindow lateWindow,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-10-12T18:04:00Z")
            Instant checkedInAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-10-12T18:20:00Z")
            Instant latePresentAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-10-12T18:20:00Z")
            Instant noShowAt) {
    }

    /** A half-open window: open from {@code opensAt}, closed from {@code closesAt}. */
    @Schema(name = "VenueBookingTimeWindow")
    public record VenueBookingTimeWindow(
            @NotNull @Schema(example = "2026-10-12T18:00:00Z") Instant opensAt,
            @NotNull @Schema(example = "2026-10-12T18:15:00Z") Instant closesAt) {
    }

    /** One booking with its history (append-only, oldest first; FR-AUD-05). */
    @Schema(name = "VenueBookingDetail")
    public record VenueBookingDetail(
            @NotNull VenueBooking booking,
            @NotNull List<VenueBookingHistoryEntry> history) {
    }

    /** A {@code booking.booking_history} row as the venue sees it (see {@link #get} for what is left out). */
    @Schema(name = "VenueBookingHistoryEntry")
    public record VenueBookingHistoryEntry(
            @NotNull @Schema(example = "2026-10-12T15:00:00Z") Instant at,
            @NotNull BookingHistoryEntryKind kind,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) BookingStatus fromStatus,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) BookingStatus toStatus,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) BookingTerminalReason terminalReason,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) BookingEventType eventType) {

        static VenueBookingHistoryEntry of(BookingHistoryEntry entry) {
            return new VenueBookingHistoryEntry(entry.at(), entry.kind(), entry.fromStatus(), entry.toStatus(),
                    entry.terminalReason(), entry.eventType());
        }
    }
}
