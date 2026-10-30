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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
import ps.e7gz.booking.internal.BookingAmounts;
import ps.e7gz.booking.internal.BookingHistoryEntry;
import ps.e7gz.booking.internal.BookingQuote;
import ps.e7gz.booking.internal.BookingQuotes;
import ps.e7gz.booking.internal.ContactVisibility;
import ps.e7gz.booking.internal.CreatedBooking;
import ps.e7gz.booking.internal.CustomerBookingRow;
import ps.e7gz.booking.internal.CustomerBookings;
import ps.e7gz.booking.internal.DisplayBucket;
import ps.e7gz.booking.internal.OnlineBookingCreation;
import ps.e7gz.booking.internal.OnlineBookingRequest;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingEventType;
import ps.e7gz.common.codes.BookingHistoryEntryKind;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.money.Money;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.MoneyView;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.PageView;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.loyalty.api.BookingFeePayments.FeePayment;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.ResultAggregate;
import ps.e7gz.messaging.api.StoredResponse;

/**
 * Customer bookings (arch 09 §3.3). Create and quote are E7-46's; My Bookings, one booking and the customer's cancel
 * are E7-49's. Every write here is in ADR-015's scope, so the Idempotency-Key is required. The customer DTOs are
 * separate from the venue ones (arch 09 §1.1): the venue phone is absent from the payload when the retention rule hides
 * it (NFR-18, FR-PTS-20; PO decision 11 Oct 2026, E7-49, D1), on every read and every replay.
 */
@RestController
@RequestMapping("/api/v1/customer/bookings")
@Tag(name = "customer-bookings")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class CustomerBookingController {

    /** {@code technical.idempotency_record.endpoint} of creation (arch 08 section 6). */
    static final String CREATE_ENDPOINT = "POST /api/v1/customer/bookings";
    static final String CANCEL_ENDPOINT = "POST /api/v1/customer/bookings/{bookingId}/cancel";
    static final String BOOKING_AGGREGATE = "BOOKING";
    static final String BOOKING_ID = "bookingId";

    private final BookingQuotes quotes;
    private final OnlineBookingCreation creation;
    private final CustomerBookings bookings;
    private final ContactVisibility contacts;
    private final Idempotency idempotency;
    private final ResponseMetaFactory meta;

    public CustomerBookingController(BookingQuotes quotes, OnlineBookingCreation creation, CustomerBookings bookings,
                                     ContactVisibility contacts, Idempotency idempotency, ResponseMetaFactory meta) {
        this.quotes = quotes;
        this.creation = creation;
        this.bookings = bookings;
        this.contacts = contacts;
        this.idempotency = idempotency;
        this.meta = meta;
    }

    /**
     * One booking per (customer, Idempotency-Key): the key's record, the booking, its reservation and its
     * {@code BookingCreated} / {@code ReservationHeld} events commit in one transaction (ADR-015, PO decision 9 Oct
     * 2026). A replay returns the stored 201 body, with the venue phone left out once the retention rule hides it
     * (arch 10 §5.1); the same key with another body is {@code IDEMPOTENCY_KEY_REUSED}.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "customerCreateBooking",
            summary = "Request a booking; PENDING until the venue answers within 10 minutes (FR-BOK-11, FR-BOK-13)",
            description = "Checks the twenty creation conditions of FR-BOK-11 in one transaction. Displayed "
                    + "availability is not a promise: a slot taken meanwhile is 409 SLOT_NO_LONGER_AVAILABLE "
                    + "(FR-AVL-11). Duration and lead time are product rules, answered with their 422 codes, not "
                    + "VALIDATION_FAILED. playMode is required for PLAYSTATION and absent otherwise. A venue with no "
                    + "price for the activity fails under FR-BOK-11 item 20 as RESOURCE_NOT_ELIGIBLE (PO decision 5 Oct "
                    + "2026). An unknown resourceId is NOT_FOUND (PO decision 9 Oct 2026). Implemented by E7-46.")
    @ApiError(status = 403, codes = {ApiErrorCode.PHONE_VERIFICATION_REQUIRED, ApiErrorCode.ACCOUNT_BANNED_FOR_THIS_ACTION,
            ApiErrorCode.ACCOUNT_DELETED})
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.SLOT_NO_LONGER_AVAILABLE)
    @ApiError(status = 422, codes = {ApiErrorCode.BOOKING_LEAD_TIME_TOO_SHORT, ApiErrorCode.BOOKING_DURATION_INVALID,
            ApiErrorCode.COMMITMENT_LIMIT_REACHED, ApiErrorCode.OVERLAPPING_COMMITMENT,
            ApiErrorCode.INTERVAL_NOT_WITHIN_ONE_SESSION, ApiErrorCode.ONLINE_INTAKE_WINDOW_CLOSED,
            ApiErrorCode.VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY, ApiErrorCode.RESOURCE_NOT_ELIGIBLE,
            ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE, ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE,
            ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public ResponseEntity<DataResponse<CustomerBookingDetail>> create(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @Valid @RequestBody CustomerBookingCreateRequest request,
            @AuthenticationPrincipal AuthenticatedSubject customer) {
        StoredResponse<CustomerBookingDetail> response = StoredResponse.of(CustomerBookingDetail.class, 201,
                        (CustomerBookingDetail detail) -> new ResultAggregate(BOOKING_AGGREGATE, detail.bookingId()))
                .replayedThrough((context, stored) -> contacts.venuePhoneVisible(context, stored.bookingId())
                        ? stored : stored.withoutVenuePhone());
        IdempotentResult<CustomerBookingDetail> result = creation.create(
                new OnlineBookingRequest(customer.id(), request.resourceId(), request.playMode(), request.startAt(),
                        request.durationMinutes()),
                idempotency.request(customer, CREATE_ENDPOINT, idempotencyKey, Map.of(), request),
                response, CustomerBookingDetail::created);
        return ResponseEntity.status(result.status()).body(meta.data(result.body()));
    }

    @GetMapping("/quote")
    @Operation(operationId = "customerQuoteBooking",
            summary = "The price a booking would have now (FR-BOK-06, FR-BOK-07, FR-PTS-13)",
            description = "The same price the booking would snapshot if created now: hourly price × nominal minutes ÷ "
                    + "60, half-up to 2 places, plus the booking fee (per booking, or per hour × nominal minutes ÷ "
                    + "60, PCR-010). bookingFeePayment is how my Points and Wallet would pay that fee now: Points "
                    + "first, then the Wallet for all of the rest or nothing (PCR-009, PCR-010). Not an availability "
                    + "check and not a hold: the created booking's own amounts are authoritative. PO decision 5 Oct "
                    + "2026 (E7-39). An unknown resourceId is NOT_FOUND (PO decision 9 Oct 2026). Implemented by "
                    + "E7-46; the fee split by E7-68.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 422, codes = {ApiErrorCode.BOOKING_DURATION_INVALID, ApiErrorCode.RESOURCE_NOT_ELIGIBLE})
    public DataResponse<CustomerBookingQuote> quote(
            @RequestParam UUID resourceId,
            @Parameter(description = "Required for PLAYSTATION, absent otherwise.") @RequestParam(required = false)
            PlayMode playMode,
            @RequestParam Instant startAt,
            @RequestParam Integer durationMinutes,
            @AuthenticationPrincipal AuthenticatedSubject customer) {
        // startAt is part of the contract but not of the price: the price is the one in force now (FR-BOK-06).
        return meta.data(CustomerBookingQuote.of(quotes.quote(customer.id(), resourceId, playMode,
                durationMinutes)));
    }

    @GetMapping
    @Operation(operationId = "customerListBookings",
            summary = "My bookings in one display bucket (FR-BOK-19)",
            description = "upcoming = PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED, ACTIVE · completed = "
                    + "COMPLETED · cancelled = VENUE_RESPONSE_EXPIRED, CANCELLED, REJECTED, NO_SHOW (FR-BOK-19). "
                    + "upcoming lists the soonest first; completed and cancelled the most recent first. Cursor-paged "
                    + "(nextCursor null on the last page). venue.phone is absent when the retention rule hides it "
                    + "(arch 10 §5.1). Implemented by E7-49.")
    public DataResponse<PageView<CustomerBookingSummary>> list(
            @RequestParam CustomerBookingBucket bucket,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            @AuthenticationPrincipal AuthenticatedSubject customer) {
        CustomerBookingRow.Page page = bookings.list(customer.id(), bucket.displayBucket(), cursor, limit);
        return meta.data(new PageView<>(page.items().stream().map(CustomerBookingSummary::of).toList(),
                page.nextCursor()));
    }

    @GetMapping("/{bookingId}")
    @Operation(operationId = "customerGetBooking",
            summary = "One of my bookings with its history (FR-BOK-20, FR-PTS-20)",
            description = "venue.phone is absent when the retention rule hides it — not null, not masked (NFR-18, "
                    + "arch 10 §5.1): terminal before start until terminalAt + 2h, at or after start until endAt + 2h, "
                    + "VENUE_RESPONSE_EXPIRED until expiry + 2h - and once that has passed it stays hidden unless a Late "
                    + "Confirm reopens it (PO decision 11 Oct 2026, E7-49, D1). Another customer's booking is "
                    + "NOT_FOUND. Implemented by E7-49.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    public DataResponse<CustomerBookingDetail> get(@PathVariable UUID bookingId,
                                                   @AuthenticationPrincipal AuthenticatedSubject customer) {
        return meta.data(CustomerBookingDetail.of(bookings.detail(customer.id(), bookingId)));
    }

    /**
     * PO decision 11 Oct 2026, E7-49 (D2): PENDING or CONFIRMED before startAt is cancelled; ACTIVE or at/after startAt
     * is DEADLINE_PASSED; VENUE_RESPONSE_EXPIRED, AWAITING_CUSTOMER_CONFIRMATION and terminal statuses are
     * BOOKING_STATE_CHANGED; another customer's booking is NOT_FOUND before any idempotency record. A replay leaves the
     * venue phone out once the retention rule hides it, through the same resolver as the reads (D1).
     */
    @PostMapping("/{bookingId}/cancel")
    @Operation(operationId = "customerCancelBooking",
            summary = "Cancel before the start time, free and without penalty (FR-BOK-31, FR-BOK-32)",
            description = "Allowed for PENDING or CONFIRMED while now < startAt, also while the venue response timer "
                    + "is paused (FR-BOK-32). ACTIVE, or at or after startAt, is DEADLINE_PASSED; "
                    + "VENUE_RESPONSE_EXPIRED, AWAITING_CUSTOMER_CONFIRMATION and a booking already terminal are "
                    + "BOOKING_STATE_CHANGED (PO decision 11 Oct 2026, E7-49). Another customer's booking is "
                    + "NOT_FOUND. Terminal reason CUSTOMER_CANCELLED; the reservation is released at once. "
                    + "Implemented by E7-49.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.BOOKING_STATE_CHANGED)
    @ApiError(status = 422, codes = {ApiErrorCode.DEADLINE_PASSED, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<CustomerBookingDetail> cancel(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID bookingId,
            @AuthenticationPrincipal AuthenticatedSubject customer) {
        StoredResponse<CustomerBookingDetail> response = StoredResponse.of(CustomerBookingDetail.class, 200,
                        (CustomerBookingDetail detail) -> new ResultAggregate(BOOKING_AGGREGATE, detail.bookingId()))
                .replayedThrough((context, stored) -> contacts.venuePhoneVisible(context, stored.bookingId())
                        ? stored : stored.withoutVenuePhone());
        IdempotentResult<CustomerBookingDetail> result = bookings.cancel(customer.id(), bookingId,
                idempotency.request(customer, CANCEL_ENDPOINT, idempotencyKey, Map.of(BOOKING_ID, bookingId), null),
                response, CustomerBookingDetail::of);
        return meta.data(result.body());
    }

    /** arch 09 §3.3 {@code ?bucket=upcoming|completed|cancelled}: lower case on the wire, so lower case here. */
    @Schema(name = "CustomerBookingBucket", enumAsRef = true)
    public enum CustomerBookingBucket {
        upcoming,
        completed,
        cancelled;

        static CustomerBookingBucket of(DisplayBucket bucket) {
            return valueOf(bucket.name().toLowerCase(Locale.ROOT));
        }

        DisplayBucket displayBucket() {
            return DisplayBucket.valueOf(name().toUpperCase(Locale.ROOT));
        }
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
            @NotNull @Schema(description = "How my Points and Wallet would pay the fee now (PCR-010).")
            CustomerBookingFeePayment bookingFeePayment,
            @NotNull MoneyView totalAmount) {

        static CustomerBookingQuote of(BookingQuote quote) {
            BookingAmounts amounts = quote.amounts();
            return new CustomerBookingQuote(MoneyView.of(amounts.hourlyPrice()), amounts.nominalBookedMinutes(),
                    MoneyView.of(amounts.venuePriceTotal()), CustomerBookingFee.of(amounts.bookingFee()),
                    CustomerBookingFeePayment.of(quote.feePayment()), MoneyView.of(amounts.totalAmount()));
        }
    }

    /** The booking fee; EGP 0 at launch, shown as free (FR-PTS-13). */
    @Schema(name = "CustomerBookingFee")
    public record CustomerBookingFee(
            @NotNull @Pattern(regexp = "^\\d+\\.\\d{2}$") @Schema(example = "0.00") String amount,
            @NotNull MoneyView.Currency currency,
            @NotNull @Schema(description = "True when the fee is zero (FR-PTS-13).") Boolean displayAsFree) {

        static CustomerBookingFee of(Money fee) {
            return new CustomerBookingFee(fee.toString(), MoneyView.Currency.EGP, fee.isZero());
        }
    }

    /**
     * How the booking fee is paid (PCR-009, PCR-010): Points first - one fee unit pays an hour of fee, or the whole
     * fee per booking - then the Wallet for all of the rest or nothing; the payment provider collects what is left
     * (ADR-018). All zero while the fee is EGP 0.
     */
    @Schema(name = "CustomerBookingFeePayment")
    public record CustomerBookingFeePayment(
            @NotNull @Schema(description = "Points spent on the fee.", example = "20000") Integer pointsUsed,
            @NotNull @Schema(description = "The part of the fee the Points pay.") MoneyView paidByPoints,
            @NotNull @Schema(description = "The part the Wallet pays: all of what Points leave, or nothing.")
            MoneyView paidByWallet,
            @NotNull @Schema(description = "The rest, collected by the payment provider.") MoneyView leftToPay) {

        static CustomerBookingFeePayment of(FeePayment payment) {
            return new CustomerBookingFeePayment(payment.pointsUsed(), MoneyView.of(payment.paidByPoints()),
                    MoneyView.of(payment.paidByWallet()), MoneyView.of(payment.leftToPay()));
        }
    }

    /** The venue on a customer booking. {@code phone} is absent, never null, when hidden (NFR-18, FR-PTS-20). */
    @Schema(name = "CustomerBookingVenue")
    public record CustomerBookingVenue(
            @NotNull @Schema(example = "7b1f0c9e-2d3a-4e5b-8c6d-9e0f1a2b3c4d") UUID id,
            @NotNull @Schema(example = "Level Up Lounge") String name,
            @JsonInclude(JsonInclude.Include.NON_NULL)
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

        static CustomerBookingResource of(CustomerBookingRow row) {
            return new CustomerBookingResource(row.resourceName(), row.activity(), row.consoleType(), row.playMode());
        }
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

        static CustomerBookingSummary of(CustomerBookingRow row) {
            return new CustomerBookingSummary(row.bookingId(), row.status(),
                    CustomerBookingBucket.of(row.displayBucket()), row.terminalReason(),
                    new CustomerBookingVenue(row.venueId(), row.venueName(), row.venuePhone()),
                    CustomerBookingResource.of(row), row.startAt(), row.endAt(), MoneyView.of(row.totalAmount()),
                    row.venueResponseExpiresAt());
        }
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
            @NotNull @Schema(description = "How Points and the Wallet paid the fee at creation (PCR-010).")
            CustomerBookingFeePayment bookingFeePayment,
            @NotNull MoneyView totalAmount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "While PENDING: the venue's answer deadline, an instant — never a countdown (NFR-10).",
                    example = "2026-10-12T15:10:00Z") Instant venueResponseExpiresAt,
            @NotNull @Schema(description = "True while an emergency pauses the venue response timer (FR-BOK-32).")
            Boolean venueResponsePaused,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "While AWAITING_CUSTOMER_CONFIRMATION only (arch 09 §3.3).")
            CustomerBookingLateConfirm lateConfirm,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "Independent of status (FR-ATT-07).") AttendanceResult attendanceResult,
            @NotNull @Schema(description = "Append-only, oldest first (FR-AUD-05).") List<CustomerBookingHistoryEntry> history) {

        /**
         * A booking just created: PENDING with its venue deadline, the venue phone open (FR-DIS-09), and its one
         * history row, Booking Created → PENDING (Product v5.0 §23.5).
         */
        static CustomerBookingDetail created(CreatedBooking booking) {
            BookingAmounts amounts = booking.amounts();
            return new CustomerBookingDetail(booking.bookingId(), BookingStatus.PENDING, CustomerBookingBucket.upcoming,
                    null, new CustomerBookingVenue(booking.venueId(), booking.venueName(), booking.venuePhone()),
                    new CustomerBookingResource(booking.resourceName(), booking.activity(), booking.consoleType(),
                            booking.playMode()),
                    booking.startAt(), booking.endAt(), amounts.nominalBookedMinutes(),
                    MoneyView.of(amounts.venuePriceTotal()), CustomerBookingFee.of(amounts.bookingFee()),
                    CustomerBookingFeePayment.of(booking.feePayment()),
                    MoneyView.of(amounts.totalAmount()), booking.venueResponseExpiresAt(), false, null, null,
                    List.of(new CustomerBookingHistoryEntry(booking.createdAt(),
                            BookingHistoryEntryKind.STATUS_TRANSITION, null, BookingStatus.PENDING, null, null)));
        }

        /**
         * A booking as it is now, with its history. {@code attendanceResult} stays null: attendance records are E7-61's
         * (FR-ATT-07), and no attendance API exists yet. {@code lateConfirm} is the open Late Confirmation while
         * AWAITING_CUSTOMER_CONFIRMATION only - its stored deadline and whether an emergency pauses it, never the
         * pause's cause (E7-58; Sprint 3 contract, E7-54).
         */
        static CustomerBookingDetail of(CustomerBookingRow row) {
            return new CustomerBookingDetail(row.bookingId(), row.status(),
                    CustomerBookingBucket.of(row.displayBucket()), row.terminalReason(),
                    new CustomerBookingVenue(row.venueId(), row.venueName(), row.venuePhone()),
                    CustomerBookingResource.of(row), row.startAt(), row.endAt(), row.nominalBookedMinutes(),
                    MoneyView.of(row.venuePriceTotal()), CustomerBookingFee.of(row.bookingFee()),
                    CustomerBookingFeePayment.of(Objects.requireNonNull(row.feePayment(), "feePayment")),
                    MoneyView.of(row.totalAmount()), row.venueResponseExpiresAt(), row.venueResponsePaused(),
                    row.lateConfirmExpiresAt() == null ? null
                            : new CustomerBookingLateConfirm(row.lateConfirmExpiresAt(), row.lateConfirmPaused()),
                    null, row.history().stream().map(CustomerBookingHistoryEntry::of).toList());
        }

        /** The same detail with the venue phone absent (arch 10 §5.1): only a member left out, nothing changed. */
        CustomerBookingDetail withoutVenuePhone() {
            return new CustomerBookingDetail(bookingId, status, displayBucket, terminalReason,
                    new CustomerBookingVenue(venue.id(), venue.name(), null), resource, startAt, endAt,
                    nominalBookedMinutes, venuePriceTotal, bookingFee, bookingFeePayment, totalAmount,
                    venueResponseExpiresAt,
                    venueResponsePaused, lateConfirm, attendanceResult, history);
        }
    }

    /**
     * The Late Confirmation the venue sent (FR-LCF-09, FR-OPS-41; Sprint 3 contract, E7-54). arch 09 §3.3's
     * {@code pauseCauseCode} is left out: the pause cause has no closed code list (only free text in the pause
     * history), and the venue response timer carries only its {@code venueResponsePaused} flag too.
     */
    @Schema(name = "CustomerBookingLateConfirm")
    public record CustomerBookingLateConfirm(
            @NotNull @Schema(description = "MIN(initiation + 5 min, startAt): an instant, never a countdown (NFR-10). "
                    + "Moves when a pause ends, never beyond startAt.", example = "2026-10-12T17:35:00Z")
            Instant expiresAt,
            @NotNull @Schema(description = "True while an emergency or selling block pauses the timer: Accept is "
                    + "refused, Decline still works (FR-OPS-41).") Boolean paused) {
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

        static CustomerBookingHistoryEntry of(BookingHistoryEntry entry) {
            return new CustomerBookingHistoryEntry(entry.at(), entry.kind(), entry.fromStatus(), entry.toStatus(),
                    entry.terminalReason(), entry.eventType());
        }
    }
}
