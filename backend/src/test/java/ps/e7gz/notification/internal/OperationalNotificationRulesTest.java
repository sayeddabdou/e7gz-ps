package ps.e7gz.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ps.e7gz.common.codes.NotificationRecipientType;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.notification.api.NotificationRequest;

/** E7-80: the venue's operational notifications by event (Spec v5 section 52.5, FR-NTF-09, FR-OPS-29). */
class OperationalNotificationRulesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID VENUE = UUID.randomUUID();

    @Test
    void declaredGivesOwnerNoticeForAVenueEmergency() {
        List<NotificationRequest> sends = plan(IntegrationEvents.EMERGENCY_DECLARED,
                Map.of("emergencyId", UUID.randomUUID().toString(), "scope", "VENUE"));

        assertThat(sends).singleElement().satisfies(send -> {
            assertThat(send.code()).isEqualTo("VENUE_OWNER_EMERGENCY_NOTICE");
            assertThat(send.recipientType()).isEqualTo(NotificationRecipientType.VENUE);
            assertThat(send.recipientId()).isEqualTo(VENUE);
            assertThat(send.bookingId()).isNull();
            assertThat(send.params()).containsEntry("scope", "VENUE").doesNotContainKey("resourceId");
        });
    }

    @Test
    void declaredGivesOwnerNoticeForAResourceEmergency() {
        String resource = UUID.randomUUID().toString();
        List<NotificationRequest> sends = plan(IntegrationEvents.EMERGENCY_DECLARED,
                Map.of("emergencyId", UUID.randomUUID().toString(), "scope", "RESOURCE", "resourceId", resource));

        assertThat(sends).singleElement().satisfies(send -> {
            assertThat(send.code()).isEqualTo("VENUE_OWNER_EMERGENCY_NOTICE");
            assertThat(send.params()).containsEntry("scope", "RESOURCE").containsEntry("resourceId", resource);
        });
    }

    @Test
    void ownerReviewEntryGivesNotice() {
        String review = UUID.randomUUID().toString();
        List<NotificationRequest> sends = plan(IntegrationEvents.RESOURCE_RESTRICTION_CHANGED, Map.of(
                "resourceId", UUID.randomUUID().toString(), "restriction", "OWNER_REVIEW_REQUIRED",
                "ownerReviewId", review));

        assertThat(sends).singleElement().satisfies(send -> {
            assertThat(send.code()).isEqualTo("VENUE_OWNER_REVIEW_ENTERED");
            assertThat(send.recipientId()).isEqualTo(VENUE);
            assertThat(send.params()).containsEntry("ownerReviewId", review);
        });
    }

    @Test
    void ownerReviewResolutionGivesNothing() {
        assertThat(plan(IntegrationEvents.RESOURCE_RESTRICTION_CHANGED, Map.of(
                "resourceId", UUID.randomUUID().toString(), "restriction", "NONE",
                "ownerReviewId", UUID.randomUUID().toString()))).isEmpty();
    }

    @Test
    void theConsumerSubscribesToItsEvents() {
        assertThat(OperationalNotificationRules.eventTypes()).contains(IntegrationEvents.EMERGENCY_DECLARED,
                IntegrationEvents.RESOURCE_RESTRICTION_CHANGED);
    }

    static List<NotificationRequest> plan(String type, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("venueId", VENUE.toString());
        payload.putAll(extra);
        return OperationalNotificationRules.planFor(new OutboxEvent(1, type, "EMERGENCY", UUID.randomUUID(),
                JSON.valueToTree(payload), UUID.randomUUID(), Instant.EPOCH, 0));
    }
}
