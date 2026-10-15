package ps.e7gz.admin.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.PageView;

/**
 * The Platform Admin's alert inbox (NFR-29, arch 11 section 5.4, arch 12 section 6; E7-56, PO decision 15 Oct 2026).
 * Alerts are immutable facts; the only change is one acknowledgement through {@code admin.acknowledge_alert}, which
 * writes its own audit row. E7-86 builds the Admin page.
 */
@RestController
@RequestMapping("/api/v1/admin/alerts")
@Tag(name = "admin-alerts")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminAlertController {

    @GetMapping
    @Operation(operationId = "adminListAlerts", summary = "Admin alerts, newest first",
            description = "Open alerts by default (acknowledged=false); acknowledged=true lists the acknowledged ones. "
                    + "kind and severity filter exactly. Cursor paging as the other lists (arch 09 section 1.2). "
                    + "Implemented by E7-56.")
    @ApiError(status = 400, codes = ApiErrorCode.VALIDATION_FAILED)
    public DataResponse<PageView<AdminAlert>> list(
            @AuthenticationPrincipal AuthenticatedSubject admin,
            @RequestParam(required = false, defaultValue = "false") boolean acknowledged,
            @RequestParam(required = false) @Size(max = 100) String kind,
            @RequestParam(required = false) @Size(max = 100) String severity,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{alertId}/acknowledge")
    @Operation(operationId = "adminAcknowledgeAlert", summary = "Acknowledge one alert",
            description = "One acknowledgement per alert, audited (ADMIN_ALERT_ACKNOWLEDGED). The same admin "
                    + "acknowledging again gets the same alert back; another admin gets "
                    + "ADMIN_ALERT_ALREADY_ACKNOWLEDGED. Implemented by E7-56.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ALERT_ALREADY_ACKNOWLEDGED)
    public DataResponse<AdminAlert> acknowledge(@AuthenticationPrincipal AuthenticatedSubject admin,
                                                @PathVariable UUID alertId,
                                                @Valid @RequestBody(required = false) AcknowledgeAlertRequest request) {
        throw NotImplementedYet.stub();
    }

    /** An optional note, kept in the acknowledgement's audit row. */
    @Schema(name = "AcknowledgeAlertRequest")
    public record AcknowledgeAlertRequest(
            @Size(max = 500) @Schema(nullable = true, example = "Checked: the booking was cancelled by hand.")
            String reason) {
    }

    @Schema(name = "AdminAlert")
    public record AdminAlert(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID id,
            @NotNull @Schema(description = "Open text, e.g. BOOKING_INTEGRITY_FAILURE.",
                    example = "SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR") String kind,
            @NotNull @Schema(description = "SEV_1, SEV_2 or SEV_3 (arch 11 section 5.4); RISK for an abuse signal.",
                    example = "SEV_1") String severity,
            @NotNull @Schema(example = "BOOKING") String subjectType,
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID subjectId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "Diagnostic facts (ids, states, instants); never rule text.")
            Map<String, Object> payload,
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e70") UUID correlationId,
            @NotNull @Schema(example = "2026-10-15T09:00:00Z") Instant createdAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-10-15T09:10:00Z")
            Instant acknowledgedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID acknowledgedByAdminId) {
    }
}
