package ps.e7gz.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ps.e7gz.common.codes.NotificationRecipientType;
import ps.e7gz.common.codes.PointsTransactionCause;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.notification.api.NotificationRequest;

/** E7-66 (PO decision 23 Oct 2026): which points ledger rows notify the customer, and with what. */
class PointsNotificationRulesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID CUSTOMER = UUID.randomUUID();

    @Test
    void attendedCompletionPointsAreEarnedWithTheirAmount() {
        assertThat(PointsNotificationRules.planFor(event(PointsTransactionCause.ATTENDED_COMPLETION, 200)))
                .contains(NotificationRequest.of("POINTS_EARNED", NotificationRecipientType.CUSTOMER, CUSTOMER,
                        Map.of("points", 200)));
    }

    @Test
    void anAttendedCompletionThatAppliedNothingIsNotNotified() {
        // a configured value of 0 (A8) grants nothing
        assertThat(PointsNotificationRules.planFor(event(PointsTransactionCause.ATTENDED_COMPLETION, 0))).isEmpty();
    }

    @Test
    void anAdminAdjustmentEitherWayIsAnAccountChange() {
        // FR-NTF-11
        for (int applied : new int[] {150, -300}) {
            assertThat(PointsNotificationRules.planFor(event(PointsTransactionCause.ADMIN, applied)))
                    .contains(NotificationRequest.of("ADMIN_ACCOUNT_CHANGE", NotificationRecipientType.CUSTOMER,
                            CUSTOMER, Map.of()));
        }
    }

    @Test
    void anAdminDeductionFromZeroChangesNothingAndNotifiesNobody() {
        assertThat(PointsNotificationRules.planFor(event(PointsTransactionCause.ADMIN, 0))).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = PointsTransactionCause.class, names = {"REFERRAL_SIGNUP", "COMPENSATION"})
    void referralsAndCompensationsAreNotifiedElsewhere(PointsTransactionCause cause) {
        // E7-67 (referrals); E7-70 and E7-67 notify the correction or invalidation that compensates
        assertThat(PointsNotificationRules.planFor(event(cause, 1000))).isEmpty();
    }

    @Test
    void anotherEventTypeIsNotOurs() {
        OutboxEvent event = event(PointsTransactionCause.ADMIN, 100);
        assertThat(PointsNotificationRules.planFor(new OutboxEvent(event.id(), "LOYALTY_WALLET_LEDGER_COMMITTED",
                event.aggregateType(), event.aggregateId(), event.payload(), event.correlationId(),
                event.occurredAt(), 0))).isEmpty();
    }

    /** As {@code loyalty.post_points} writes it (the members this rule reads, and a few it ignores). */
    static OutboxEvent event(PointsTransactionCause cause, int applied) {
        ObjectNode payload = JSON.createObjectNode()
                .put("transactionId", UUID.randomUUID().toString())
                .put("cause", cause.name())
                .put("requestedAmount", applied)
                .put("appliedAmount", applied)
                .put("unrecoveredAmount", 0)
                .put("balanceAfter", Math.max(applied, 0))
                .put("recipientUserId", CUSTOMER.toString());
        return new OutboxEvent(42L, IntegrationEvents.LOYALTY_POINTS_LEDGER_COMMITTED, "LOYALTY", UUID.randomUUID(),
                payload, UUID.randomUUID(), Instant.parse("2026-10-23T12:00:00Z"), 0);
    }
}
