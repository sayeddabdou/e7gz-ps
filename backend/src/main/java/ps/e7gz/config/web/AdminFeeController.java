package ps.e7gz.config.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.BookingFeeMode;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.money.Money;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.MoneyView;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.config.internal.PlatformFees;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.StoredResponse;

/**
 * Platform Fees: the Global Booking Fee - per booking or per hour since PCR-010 - and the Tournament Creation Fee (arch 09
 * §5, FR-ADM-10, FR-ADM-11, arch 01 §14 A8, ADR-028; E7-68). A change goes through {@code config.change_configuration}:
 * audited with the admin and reason, effective now, for later events only — every booking keeps the fee stored at its
 * creation (FR-PTS-13). The Idempotency-Key is required (PO decision D6, 17 Oct 2026).
 */
@RestController
@RequestMapping("/api/v1/admin/fees")
@Tag(name = "admin-fees")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminFeeController {

    /** {@code technical.idempotency_record.endpoint} of the change (arch 08 section 6). */
    static final String CHANGE_ENDPOINT = "PUT /api/v1/admin/fees";

    /** The answer names no aggregate: a change touches up to four configuration versions. */
    private static final StoredResponse<AdminFees> CHANGED = StoredResponse.of(AdminFees.class, 200);

    private final PlatformFees fees;
    private final Idempotency idempotency;
    private final ResponseMetaFactory meta;

    AdminFeeController(PlatformFees fees, Idempotency idempotency, ResponseMetaFactory meta) {
        this.fees = fees;
        this.idempotency = idempotency;
        this.meta = meta;
    }

    @GetMapping
    @Operation(operationId = "adminGetFees", summary = "The platform fees and when each took effect (FR-ADM-10)",
            description = "EGP 0 per booking at launch; the customer sees a zero booking fee as FREE (FR-PTS-13). The "
                    + "booking fee mode says which amount is used: bookingFee once per booking, or bookingFeePerHour "
                    + "× nominal minutes ÷ 60 (PCR-010). A launch value shows the instant it was installed. "
                    + "Implemented by E7-68.")
    public DataResponse<AdminFees> get() {
        return meta.data(AdminFees.of(fees.current()));
    }

    @PutMapping
    @Operation(operationId = "adminChangeFees", summary = "Change one or more platform fees (FR-ADM-10, FR-ADM-11)",
            description = "Omitted fields keep their value; a value equal to the current one changes nothing; none "
                    + "given is VALIDATION_FAILED. Amounts are from 0.00. Every change takes effect now and only for "
                    + "bookings created after it; Points pay the fee of the mode in force (PCR-010). Implemented by "
                    + "E7-68.")
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminFees> change(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @Valid @RequestBody AdminFeesChangeRequest request,
            @AuthenticationPrincipal AuthenticatedSubject admin) {
        PlatformFees.Change change = new PlatformFees.Change(request.bookingFeeMode(), money(request.bookingFee()),
                money(request.bookingFeePerHour()), money(request.tournamentCreationFee()), request.reason());
        return meta.data(fees.change(admin.id(), change,
                idempotency.request(admin, CHANGE_ENDPOINT, idempotencyKey, Map.of(), request), CHANGED,
                AdminFees::of).body());
    }

    private static Money money(MoneyView view) {
        return view == null ? null : view.toMoney();
    }

    @Schema(name = "AdminFees")
    public record AdminFees(
            @NotNull @Schema(description = "PER_BOOKING: bookingFee once per booking; PER_HOUR: bookingFeePerHour × "
                    + "nominal minutes ÷ 60, half-up to 2 places (PCR-010).") AdminConfiguredFeeMode bookingFeeMode,
            @NotNull @Schema(description = "The fee of one booking in PER_BOOKING mode (FR-PTS-13).")
            AdminConfiguredMoney bookingFee,
            @NotNull @Schema(description = "The fee of one hour in PER_HOUR mode (PCR-010).")
            AdminConfiguredMoney bookingFeePerHour,
            @NotNull AdminConfiguredMoney tournamentCreationFee) {

        static AdminFees of(PlatformFees.Fees fees) {
            return new AdminFees(
                    new AdminConfiguredFeeMode(fees.bookingFeeMode().value(), fees.bookingFeeMode().effectiveFrom()),
                    AdminConfiguredMoney.of(fees.bookingFee()), AdminConfiguredMoney.of(fees.bookingFeePerHour()),
                    AdminConfiguredMoney.of(fees.tournamentCreationFee()));
        }
    }

    @Schema(name = "AdminConfiguredMoney")
    public record AdminConfiguredMoney(
            @NotNull MoneyView value,
            @NotNull @Schema(description = "When this value took effect.", example = "2026-10-01T00:00:00Z")
            Instant effectiveFrom) {

        static AdminConfiguredMoney of(PlatformFees.Configured<Money> configured) {
            return new AdminConfiguredMoney(MoneyView.of(configured.value()), configured.effectiveFrom());
        }
    }

    @Schema(name = "AdminConfiguredFeeMode")
    public record AdminConfiguredFeeMode(
            @NotNull BookingFeeMode value,
            @NotNull @Schema(description = "When this value took effect.", example = "2026-10-01T00:00:00Z")
            Instant effectiveFrom) {
    }

    @Schema(name = "AdminFeesChangeRequest")
    public record AdminFeesChangeRequest(
            @Schema(nullable = true) BookingFeeMode bookingFeeMode,
            @Valid @Schema(nullable = true) MoneyView bookingFee,
            @Valid @Schema(nullable = true) MoneyView bookingFeePerHour,
            @Valid @Schema(nullable = true) MoneyView tournamentCreationFee,
            @NotBlank @Size(min = 1, max = 500)
            @Schema(example = "Booking fee switched on for the new season.") String reason) {
    }
}
