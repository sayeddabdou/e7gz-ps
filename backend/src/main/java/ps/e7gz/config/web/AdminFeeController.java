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
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.MoneyView;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;

/**
 * Platform Fees: the Global Booking Fee and the Tournament Creation Fee (arch 09 §5, FR-ADM-10, FR-ADM-11, arch 01 §14
 * A8, ADR-028). Stubs of the Sprint 4 contract (E7-65) until E7-68. A change goes through
 * {@code config.change_configuration}: audited with the admin and reason, effective now, for later events only —
 * every booking keeps the fee stored at its creation (FR-PTS-13). The Idempotency-Key is required (PO decision D6,
 * 17 Oct 2026).
 */
@RestController
@RequestMapping("/api/v1/admin/fees")
@Tag(name = "admin-fees")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminFeeController {

    @GetMapping
    @Operation(operationId = "adminGetFees", summary = "The two platform fees and when each took effect (FR-ADM-10)",
            description = "EGP 0 at launch; the customer sees a zero booking fee as FREE (FR-PTS-13). Implemented by "
                    + "E7-68.")
    public DataResponse<AdminFees> get() {
        throw NotImplementedYet.stub();
    }

    @PutMapping
    @Operation(operationId = "adminChangeFees", summary = "Change one or both platform fees (FR-ADM-10, FR-ADM-11)",
            description = "Omitted fields keep their value; a value equal to the current one changes nothing; none "
                    + "given is VALIDATION_FAILED. Amounts are from 0.00. Implemented by E7-68.")
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminFees> change(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @Valid @RequestBody AdminFeesChangeRequest request) {
        throw NotImplementedYet.stub();
    }

    @Schema(name = "AdminFees")
    public record AdminFees(
            @NotNull @Schema(description = "Per booking (FR-PTS-13).") AdminConfiguredMoney bookingFee,
            @NotNull AdminConfiguredMoney tournamentCreationFee) {
    }

    @Schema(name = "AdminConfiguredMoney")
    public record AdminConfiguredMoney(
            @NotNull MoneyView value,
            @NotNull @Schema(description = "When this value took effect.", example = "2026-10-01T00:00:00Z")
            Instant effectiveFrom) {
    }

    @Schema(name = "AdminFeesChangeRequest")
    public record AdminFeesChangeRequest(
            @Valid @Schema(nullable = true) MoneyView bookingFee,
            @Valid @Schema(nullable = true) MoneyView tournamentCreationFee,
            @NotBlank @Size(min = 1, max = 500)
            @Schema(example = "Booking fee switched on for the new season.") String reason) {
    }
}
