package ps.e7gz.notification.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.DevicePlatform;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.PageView;

/**
 * Customer notifications: the in-app list and the push device tokens (arch 09 §3.4, FR-NTF-02, arch 08 §4.4–§4.5).
 * Stubs until E7-43. Notifications are language-neutral codes; the app owns the text (ADR-021). A device token is
 * revoked, never deleted (arch 08 §4); revoke routes are a PO decision, 5 Oct 2026 (E7-39).
 */
@RestController
@RequestMapping("/api/v1/customer/notifications")
@Tag(name = "customer-notifications")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class CustomerNotificationController {

    @GetMapping
    @Operation(operationId = "customerListNotifications", summary = "My notifications, newest first (FR-NTF-02)",
            description = "Implemented by E7-43.")
    public DataResponse<PageView<CustomerNotification>> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "customerMarkNotificationRead", summary = "Mark one notification as read",
            description = "Reading an already-read notification changes nothing (204). Implemented by E7-43.")
    public void markRead(@PathVariable UUID notificationId) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/devices")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "customerRegisterDevice", summary = "Register this phone's push token (FR-NTF-02)",
            description = "notification.device_token. A token already active for this customer is seen again, not "
                    + "duplicated (ux_device_token_active). Implemented by E7-43.")
    public DataResponse<CustomerDevice> registerDevice(@Valid @RequestBody CustomerDeviceRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/devices/{deviceId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "customerRevokeDevice",
            summary = "Stop push to this phone, e.g. on logout (revoked, never deleted; arch 08 §4)",
            description = "Revoking an already-revoked token changes nothing (204). Implemented by E7-43.")
    public void revokeDevice(@PathVariable UUID deviceId) {
        throw NotImplementedYet.stub();
    }

    /** A {@code notification.notification} row for this customer. */
    @Schema(name = "CustomerNotification")
    public record CustomerNotification(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID id,
            @NotNull @Schema(description = "A notification_catalogue code; the app owns the text (ADR-021).",
                    example = "BOOKING_CONFIRMED") String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID bookingId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID tournamentId,
            @NotNull @Schema(description = "Machine values for the text; never prose.") Map<String, Object> params,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-10-12T15:05:00Z")
            Instant readAt,
            @NotNull @Schema(example = "2026-10-12T15:04:00Z") Instant createdAt) {
    }

    /** {@code notification.device_token} input: an FCM token from the app. */
    @Schema(name = "CustomerDeviceRequest")
    public record CustomerDeviceRequest(
            @NotBlank @Size(max = 4096) @Schema(example = "fcm-token-…") String token,
            @NotNull DevicePlatform platform) {
    }

    @Schema(name = "CustomerDevice")
    public record CustomerDevice(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID id,
            @NotNull DevicePlatform platform,
            @NotNull @Schema(example = "2026-10-12T15:00:00Z") Instant createdAt) {
    }
}
