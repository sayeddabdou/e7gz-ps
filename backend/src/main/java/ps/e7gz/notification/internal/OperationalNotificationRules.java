package ps.e7gz.notification.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import ps.e7gz.common.codes.NotificationRecipientType;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.messaging.api.PermanentDeliveryFailure;
import ps.e7gz.notification.api.NotificationRequest;

/**
 * The venue's operational notifications that are not about one booking (Spec v5 section 52.5, FR-NTF-09; E7-80, PO
 * decisions 25 Nov 2026). The ONE place they are decided; {@link OperationalNotificationConsumer} delivers them. A
 * booking an operational cascade cancels is told through {@code BookingCancelled} ({@link BookingNotificationRules}).
 *
 * <ul>
 *   <li>{@code EmergencyDeclared}: "Owner notification of Emergency Closure, Resource Emergency Outage"
 *       ({@code VENUE_OWNER_EMERGENCY_NOTICE}). One venue login, so the owner is the venue itself.</li>
 *   <li>{@code EmergencyAwaitingConfirmation}: "Emergency operational reminders" (section 42.7, FR-OPS-25;
 *       {@code VENUE_EMERGENCY_REMINDER}), once - the expected end passed with the Emergency still active, which is also
 *       when manual confirmation is required (PO decision 25 Nov 2026 R2). None when that step entered an Owner
 *       Review: {@code VENUE_OWNER_REVIEW_ENTERED} says it.</li>
 *   <li>{@code ResourceRestrictionChanged} into OWNER_REVIEW_REQUIRED: "Owner-review entry"
 *       ({@code VENUE_OWNER_REVIEW_ENTERED}, FR-OPS-29). Its resolution tells no one.</li>
 * </ul>
 *
 * <p>Params, never prose (ADR-021): the ids the Dashboard opens.
 */
final class OperationalNotificationRules {

    static final String OWNER_EMERGENCY_NOTICE = "VENUE_OWNER_EMERGENCY_NOTICE";
    static final String OWNER_REVIEW_ENTERED = "VENUE_OWNER_REVIEW_ENTERED";
    static final String OWNER_REVIEW_REQUIRED = "OWNER_REVIEW_REQUIRED";
    static final String EMERGENCY_REMINDER = "VENUE_EMERGENCY_REMINDER";

    private static final Map<String, Function<OutboxEvent, List<NotificationRequest>>> RULES = Map.of(
            IntegrationEvents.EMERGENCY_DECLARED, OperationalNotificationRules::declared,
            IntegrationEvents.EMERGENCY_AWAITING_CONFIRMATION, OperationalNotificationRules::awaitingConfirmation,
            IntegrationEvents.RESOURCE_RESTRICTION_CHANGED, OperationalNotificationRules::restrictionChanged);

    private OperationalNotificationRules() {
    }

    static Set<String> eventTypes() {
        return RULES.keySet();
    }

    static List<NotificationRequest> planFor(OutboxEvent event) {
        Function<OutboxEvent, List<NotificationRequest>> rule = RULES.get(event.eventType());
        if (rule == null) {
            throw new PermanentDeliveryFailure("No operational notification rule for " + event.eventType());
        }
        return rule.apply(event);
    }

    private static List<NotificationRequest> declared(OutboxEvent event) {
        return List.of(toVenue(event, OWNER_EMERGENCY_NOTICE, emergencyParams(event)));
    }

    private static List<NotificationRequest> awaitingConfirmation(OutboxEvent event) {
        if (event.payload().path("ownerReviewEntered").asBoolean(false)) {
            return List.of();
        }
        return List.of(toVenue(event, EMERGENCY_REMINDER, emergencyParams(event)));
    }

    private static Map<String, Object> emergencyParams(OutboxEvent event) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("emergencyId", event.text("emergencyId"));
        params.put("scope", event.text("scope"));
        if (event.payload().path("resourceId").isTextual()) {
            params.put("resourceId", event.text("resourceId"));
        }
        return params;
    }

    private static List<NotificationRequest> restrictionChanged(OutboxEvent event) {
        if (!OWNER_REVIEW_REQUIRED.equals(event.text("restriction"))) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("resourceId", event.text("resourceId"));
        params.put("ownerReviewId", event.text("ownerReviewId"));
        return List.of(toVenue(event, OWNER_REVIEW_ENTERED, params));
    }

    static NotificationRequest toVenue(OutboxEvent event, String code, Map<String, Object> params) {
        UUID venue = event.uuid("venueId");
        return NotificationRequest.of(code, NotificationRecipientType.VENUE, venue, params);
    }
}
