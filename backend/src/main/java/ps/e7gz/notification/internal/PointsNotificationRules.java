package ps.e7gz.notification.internal;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.codes.NotificationRecipientType;
import ps.e7gz.common.codes.PointsTransactionCause;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.notification.api.NotificationRequest;

/**
 * Which customer notification a points ledger row gives (E7-66, PO decision 23 Oct 2026; Spec v5 sections 52.2 and
 * 52.3). Pure: the event alone decides, so a retry decides the same.
 * <ul>
 *   <li>ATTENDED_COMPLETION that applied points: POINTS_EARNED (In-App + Push) with {@code points} - the customer
 *       app's "You earned {points} points.".</li>
 *   <li>ADMIN that changed the balance: ADMIN_ACCOUNT_CHANGE (In-App + Email), FR-NTF-11 ("the Customer is notified
 *       whenever an Admin materially changes ... Points"). A deduction from a zero balance changes nothing and
 *       notifies nobody.</li>
 *   <li>REFERRAL_SIGNUP that applied points (E7-67): POINTS_EARNED to the referrer, with {@code points} - a referral
 *       reward is points earned (Spec v5 section 52.2, FR-PTS-06).</li>
 *   <li>A COMPENSATION of a referral (it names the referral): ADMIN_ACCOUNT_CHANGE (In-App + Email) - only an admin
 *       invalidates a rewarded referral, and taking its reward back materially changes the referrer's referral reward
 *       (FR-NTF-11, FR-PTS-09), even when the zero floor leaves nothing to deduct (E7-67).</li>
 *   <li>A COMPENSATION of a booking reward follows an admin attendance correction, which notifies the customer
 *       itself (E7-70).</li>
 * </ul>
 */
final class PointsNotificationRules {

    static final String POINTS_EARNED = "POINTS_EARNED";
    static final String ADMIN_ACCOUNT_CHANGE = "ADMIN_ACCOUNT_CHANGE";
    static final String POINTS = "points";

    private PointsNotificationRules() {
    }

    static Optional<NotificationRequest> planFor(OutboxEvent event) {
        if (!IntegrationEvents.LOYALTY_POINTS_LEDGER_COMMITTED.equals(event.eventType())) {
            return Optional.empty();
        }
        UUID customer = event.uuid("recipientUserId");
        int applied = event.payload().path("appliedAmount").asInt(0);
        return switch (PointsTransactionCause.valueOf(event.text("cause"))) {
            case ATTENDED_COMPLETION -> applied > 0
                    ? Optional.of(NotificationRequest.of(POINTS_EARNED, NotificationRecipientType.CUSTOMER, customer,
                            Map.of(POINTS, applied)))
                    : Optional.empty();
            case ADMIN -> applied != 0
                    ? Optional.of(NotificationRequest.of(ADMIN_ACCOUNT_CHANGE, NotificationRecipientType.CUSTOMER,
                            customer, Map.of()))
                    : Optional.empty();
            case REFERRAL_SIGNUP -> applied > 0
                    ? Optional.of(NotificationRequest.of(POINTS_EARNED, NotificationRecipientType.CUSTOMER, customer,
                            Map.of(POINTS, applied)))
                    : Optional.empty();
            case COMPENSATION -> event.payload().path("referralId").isTextual()
                    ? Optional.of(NotificationRequest.of(ADMIN_ACCOUNT_CHANGE, NotificationRecipientType.CUSTOMER,
                            customer, Map.of()))
                    : Optional.empty();
        };
    }
}
