package ps.e7gz.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ps.e7gz.common.codes.NotificationRecipientType;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.notification.api.NotificationRequest;

/** E7-72 (Spec v5 sections 52.2 and 52.3): which customer notification each Verified Player transition gives. */
class VerifiedPlayerNotificationRulesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID CUSTOMER = UUID.randomUUID();

    @ParameterizedTest
    @CsvSource({
            "NOT_VERIFIED, VERIFIED,     TRUST_QUALIFICATION, VERIFIED_PLAYER_UNLOCKED",
            "NOT_VERIFIED, VERIFIED,     TRUST_CORRECTION,    VERIFIED_PLAYER_UNLOCKED",
            "REVOKED,      VERIFIED,     TRUST_CORRECTION,    VERIFIED_PLAYER_UNLOCKED",
            "VERIFIED,     REVOKED,      TRUST_CORRECTION,    VERIFIED_PLAYER_REVOKED",
            "VERIFIED,     REVOKED,      ADMIN,               VERIFIED_PLAYER_REVOKED",
            "VERIFIED,     SUSPENDED,    ADMIN,               VERIFIED_PLAYER_SUSPENDED",
            "SUSPENDED,    VERIFIED,     ADMIN,               VERIFIED_PLAYER_RESTORED",
            "REVOKED,      NOT_VERIFIED, ADMIN,               VERIFIED_PLAYER_RESTORED"})
    void eachTransitionNotifiesTheCustomerOnce(String from, String to, String cause, String code) {
        assertThat(VerifiedPlayerNotificationRules.planFor(event(from, to, cause)))
                .contains(NotificationRequest.of(code, NotificationRecipientType.CUSTOMER, CUSTOMER, Map.of()));
    }

    @Test
    void anotherEventIsNotItsConcern() {
        OutboxEvent other = new OutboxEvent(42L, IntegrationEvents.BOOKING_COMPLETED, "BOOKING", UUID.randomUUID(),
                JSON.createObjectNode(), UUID.randomUUID(), Instant.parse("2026-11-17T12:00:00Z"), 0);
        assertThat(VerifiedPlayerNotificationRules.planFor(other)).isEmpty();
    }

    static OutboxEvent event(String from, String to, String cause) {
        ObjectNode payload = JSON.createObjectNode()
                .put("userId", CUSTOMER.toString())
                .put("transitionId", UUID.randomUUID().toString())
                .put("fromStatus", from)
                .put("toStatus", to)
                .put("cause", cause);
        return new OutboxEvent(42L, IntegrationEvents.VERIFIED_PLAYER_TRANSITIONED, "USER", CUSTOMER, payload,
                UUID.randomUUID(), Instant.parse("2026-11-17T12:00:00Z"), 0);
    }
}
