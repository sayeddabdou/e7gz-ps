package ps.e7gz.notification.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.PageView;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.notification.internal.VenueNotifications;

/**
 * The venue's in-Dashboard notification list (FR-NTF-09, arch 08 section 4.4, arch 09 section 4.1; E7-55, PO decision
 * 12 Oct 2026). Same shape as the customer's list; notifications are language-neutral codes and the Dashboard owns the
 * text in EN and AR (ADR-021, FR-VEN-40). Web Push and the live stream (E7-44) are the other two venue surfaces.
 */
@RestController
@RequestMapping("/api/v1/venue/notifications")
@Tag(name = "venue-notifications")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenueNotificationController {

    private final VenueNotifications notifications;
    private final ResponseMetaFactory meta;

    public VenueNotificationController(VenueNotifications notifications, ResponseMetaFactory meta) {
        this.notifications = notifications;
        this.meta = meta;
    }

    @GetMapping
    @Operation(operationId = "venueListNotifications", summary = "The venue's notifications, newest first (FR-NTF-09)",
            description = "Cursor paging as GET /customer/notifications. Implemented by E7-55.")
    public DataResponse<PageView<VenueNotification>> list(
            @AuthenticationPrincipal AuthenticatedSubject venue,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        VenueNotifications.Page page = notifications.list(venue.id(), cursor, limit);
        return meta.data(new PageView<>(page.items().stream().map(VenueNotification::of).toList(),
                page.nextCursor()));
    }

    @PostMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "venueMarkNotificationRead", summary = "Mark one notification as read",
            description = "Reading an already-read notification changes nothing (204); another venue's notification "
                    + "is NOT_FOUND. Implemented by E7-55.")
    public void markRead(@AuthenticationPrincipal AuthenticatedSubject venue, @PathVariable UUID notificationId) {
        notifications.markRead(venue.id(), notificationId);
    }

    @Schema(name = "VenueNotification")
    public record VenueNotification(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID id,
            @NotNull @Schema(description = "A notification_catalogue code; the Dashboard owns the text (ADR-021).",
                    example = "VENUE_NEW_BOOKING_REQUEST") String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID bookingId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID tournamentId,
            @NotNull @Schema(description = "Machine values for the text; never prose.") Map<String, Object> params,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-10-12T15:05:00Z")
            Instant readAt,
            @NotNull @Schema(example = "2026-10-12T15:04:00Z") Instant createdAt) {

        static VenueNotification of(VenueNotifications.Item item) {
            return new VenueNotification(item.id(), item.code(), item.bookingId(), item.tournamentId(),
                    item.params(), item.readAt(), item.createdAt());
        }
    }
}
