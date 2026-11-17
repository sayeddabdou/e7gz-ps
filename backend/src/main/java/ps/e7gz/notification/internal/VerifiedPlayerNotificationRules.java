package ps.e7gz.notification.internal;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.codes.NotificationRecipientType;
import ps.e7gz.common.codes.VerifiedPlayerStatus;
import ps.e7gz.common.codes.VerifiedPlayerTransitionCause;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.notification.api.NotificationRequest;

/**
 * Which customer notification a Verified Player transition gives (E7-72; Spec v5 sections 52.2 and 52.3). Pure: the
 * event alone decides, so a retry decides the same. Codes from the launch catalogue
 * ({@code notification.notification_catalogue}).
 * <ul>
 *   <li>An automatic VERIFIED (first qualification, a retroactively discovered one, or the regain after a correction
 *       revocation): VERIFIED_PLAYER_UNLOCKED (In-App + Push, section 52.2).</li>
 *   <li>An admin SUSPENDED: VERIFIED_PLAYER_SUSPENDED (In-App + Email, section 52.3).</li>
 *   <li>REVOKED, by an admin or by a trust correction: VERIFIED_PLAYER_REVOKED (In-App + Email, section 52.3).</li>
 *   <li>An admin Restore, to VERIFIED or NOT_VERIFIED: VERIFIED_PLAYER_RESTORED (In-App + Email, section 52.3).</li>
 * </ul>
 * The Platform Admin's "Verified Player Revoked" is an admin alert, not a notification (PO decision 19 Oct 2026).
 */
final class VerifiedPlayerNotificationRules {

    static final String UNLOCKED = "VERIFIED_PLAYER_UNLOCKED";
    static final String SUSPENDED = "VERIFIED_PLAYER_SUSPENDED";
    static final String REVOKED = "VERIFIED_PLAYER_REVOKED";
    static final String RESTORED = "VERIFIED_PLAYER_RESTORED";

    private VerifiedPlayerNotificationRules() {
    }

    static Optional<NotificationRequest> planFor(OutboxEvent event) {
        if (!IntegrationEvents.VERIFIED_PLAYER_TRANSITIONED.equals(event.eventType())) {
            return Optional.empty();
        }
        UUID customer = event.uuid("userId");
        VerifiedPlayerStatus to = VerifiedPlayerStatus.valueOf(event.text("toStatus"));
        boolean admin = VerifiedPlayerTransitionCause.valueOf(event.text("cause")) == VerifiedPlayerTransitionCause.ADMIN;
        String code = switch (to) {
            case VERIFIED -> admin ? RESTORED : UNLOCKED;
            case NOT_VERIFIED -> RESTORED;                                // only an admin Restore leads here
            case SUSPENDED -> SUSPENDED;
            case REVOKED -> REVOKED;
        };
        return Optional.of(NotificationRequest.of(code, NotificationRecipientType.CUSTOMER, customer, Map.of()));
    }
}
