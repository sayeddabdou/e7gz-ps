package ps.e7gz.notification.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.notification.internal.DeliveryRecovery;

/**
 * Admin "try again" for a notification delivery in {@code DEAD_LETTER} (arch 09 section 5, arch 08 section 4.3; PO
 * decisions 9 and 10 Oct 2026, E7-41/E7-43).
 */
@RestController
@RequestMapping("/api/v1/admin/notification-deliveries")
@Tag(name = "admin-notification-deliveries")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminNotificationDeliveryController {

    private final DeliveryRecovery recovery;

    public AdminNotificationDeliveryController(DeliveryRecovery recovery) {
        this.recovery = recovery;
    }

    @PostMapping("/{deliveryId}/recover")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "adminRecoverNotificationDelivery",
            summary = "Try again: send a dead-lettered notification delivery once more",
            description = "notification.recover_dead_letter moves the delivery back to PENDING and writes the audit row; "
                    + "the worker then sends it again with a fresh set of attempts. Only a DEAD_LETTER delivery can be "
                    + "recovered.")
    @ApiError(status = 404, codes = {ApiErrorCode.NOT_FOUND})
    @ApiError(status = 409, codes = {ApiErrorCode.NOTIFICATION_DELIVERY_NOT_DEAD_LETTER})
    public void recover(@AuthenticationPrincipal AuthenticatedSubject admin, @PathVariable UUID deliveryId,
                        @Valid @RequestBody RecoverDeliveryRequest request) {
        recovery.recover(admin.id(), deliveryId, request.reason());
    }

    /** The mandatory reason of an admin remediation (FR-AUD-06), kept in the audit row. */
    @Schema(name = "RecoverDeliveryRequest")
    public record RecoverDeliveryRequest(
            @NotBlank @Size(max = 500) @Schema(example = "The SMTP provider was down; it is back.") String reason) {
    }
}
