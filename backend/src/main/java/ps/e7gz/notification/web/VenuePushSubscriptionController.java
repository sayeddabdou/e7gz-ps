package ps.e7gz.notification.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;

/**
 * Venue Dashboard Web Push, for when the Dashboard is closed (ADR-014, arch 08 §5, 01 §14 A6). Stubs until E7-44.
 * Routes are a PO decision, 5 Oct 2026 (E7-39): arch 09 names none. A subscription is revoked, never deleted
 * (arch 08 §4); its fields are {@code notification.venue_push_subscription}'s.
 */
@RestController
@RequestMapping("/api/v1/venue/push-subscriptions")
@Tag(name = "venue-push")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenuePushSubscriptionController {

    @GetMapping("/vapid-public-key")
    @Operation(operationId = "venueGetVapidPublicKey",
            summary = "The VAPID public key the browser needs to subscribe (applicationServerKey)",
            description = "Implemented by E7-44.")
    public DataResponse<VenueVapidPublicKey> vapidPublicKey() {
        throw NotImplementedYet.stub();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "venueCreatePushSubscription",
            summary = "Register this browser's push subscription (PushSubscription.toJSON())",
            description = "An endpoint already active for this venue account is seen again, not duplicated "
                    + "(one active row per endpoint). Implemented by E7-44.")
    public DataResponse<VenuePushSubscription> subscribe(@Valid @RequestBody VenuePushSubscriptionRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{subscriptionId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "venueRevokePushSubscription",
            summary = "Stop push to this browser, e.g. on logout (revoked, never deleted; arch 08 §4)",
            description = "Revoking an already-revoked subscription changes nothing (204). Implemented by E7-44.")
    public void revoke(@PathVariable UUID subscriptionId) {
        throw NotImplementedYet.stub();
    }

    @Schema(name = "VenueVapidPublicKey")
    public record VenueVapidPublicKey(
            @NotNull @Schema(description = "URL-safe base64, as PushManager.subscribe expects.",
                    example = "BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkvMeAtA3LFgDzkrxZJjSgSnfckjBJuBkr3qBUYIHBQFLXYp5Nksh8U")
            String publicKey) {
    }

    /** The browser's PushSubscription: endpoint (https) and its two keys ({@code ck_venue_push_subscription_values}). */
    @Schema(name = "VenuePushSubscriptionRequest")
    public record VenuePushSubscriptionRequest(
            @NotBlank @Size(max = 2048) @Pattern(regexp = "^https://.+")
            @Schema(example = "https://fcm.googleapis.com/fcm/send/abc123") String endpoint,
            @NotNull @Valid VenuePushSubscriptionKeys keys,
            @Size(max = 512) @Schema(nullable = true, example = "Mozilla/5.0 …") String userAgent) {
    }

    @Schema(name = "VenuePushSubscriptionKeys")
    public record VenuePushSubscriptionKeys(
            @NotBlank @Size(max = 512) @Schema(example = "BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQtUbVlUls0VJXg7A8u-Ts1XbjhazAkj7I99e8QcYP7DkM")
            String p256dh,
            @NotBlank @Size(max = 512) @Schema(example = "tBHItJI5svbpez7KI4CCXg") String auth) {
    }

    @Schema(name = "VenuePushSubscription")
    public record VenuePushSubscription(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID id,
            @NotNull @Schema(example = "2026-10-12T15:00:00Z") Instant createdAt) {
    }
}
