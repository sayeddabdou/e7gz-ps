package ps.e7gz.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.notification.internal.NotificationTemplates.Language;
import ps.e7gz.notification.internal.RefundNotificationRules.Refund;
import ps.e7gz.notification.internal.RefundNotificationRules.RefundedAs;

/** E7-80 (PO decisions 25 Nov 2026, Q6, R1, R4, R5): which refund confirmation a ledger event gives. */
class RefundNotificationRulesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** R4: the operational set, decided by the booking's terminal reason. */
    private static final Set<BookingTerminalReason> OPERATIONAL = Set.of(
            BookingTerminalReason.VENUE_EMERGENCY_CLOSURE, BookingTerminalReason.RESOURCE_EMERGENCY_OUTAGE,
            BookingTerminalReason.RESOURCE_OWNER_REVIEW_REQUIRED, BookingTerminalReason.VENUE_CLOSURE,
            BookingTerminalReason.VENUE_ACCOUNT_DISABLED, BookingTerminalReason.SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR,
            BookingTerminalReason.VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY,
            BookingTerminalReason.RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY,
            BookingTerminalReason.LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY);

    @ParameterizedTest
    @EnumSource(BookingTerminalReason.class)
    void theCodeFollowsTheTerminalReason(BookingTerminalReason reason) {
        assertThat(RefundNotificationRules.code(reason)).isEqualTo(OPERATIONAL.contains(reason)
                ? "OPERATIONAL_REFUND_CONFIRMED" : "BOOKING_FEE_REFUND_CONFIRMED");
    }

    @Test
    void ordinaryReasonsGiveTheFeeRefundCode() {
        for (BookingTerminalReason reason : Arrays.asList(BookingTerminalReason.USER_BANNED,
                BookingTerminalReason.VENUE_OPENING_HOURS_CHANGED, BookingTerminalReason.CUSTOMER_CANCELLED,
                BookingTerminalReason.VENUE_CANCELLED_CONFIRMED_BOOKING, BookingTerminalReason.VENUE_REJECTED)) {
            assertThat(RefundNotificationRules.code(reason)).as(reason.name())
                    .isEqualTo("BOOKING_FEE_REFUND_CONFIRMED");
        }
    }

    @Test
    void aWalletRefundCreditIsARefund() {
        UUID booking = UUID.randomUUID();
        Optional<Refund> refund = RefundNotificationRules.refundOf(event(IntegrationEvents.LOYALTY_WALLET_LEDGER_COMMITTED,
                Map.of("refundId", UUID.randomUUID().toString(), "bookingId", booking.toString(), "amount", 25.00)));

        assertThat(refund).contains(new Refund(RefundedAs.WALLET, booking, null, "25.00"));
    }

    @Test
    void walletPaymentGivesNothing() {
        assertThat(RefundNotificationRules.refundOf(event(IntegrationEvents.LOYALTY_WALLET_LEDGER_COMMITTED,
                Map.of("receiptId", UUID.randomUUID().toString(), "bookingId", UUID.randomUUID().toString(),
                        "amount", -25.00)))).isEmpty();
    }

    @Test
    void zeroAmountGivesNothing() {
        assertThat(RefundNotificationRules.refundOf(event(IntegrationEvents.LOYALTY_WALLET_LEDGER_COMMITTED,
                Map.of("refundId", UUID.randomUUID().toString(), "bookingId", UUID.randomUUID().toString(),
                        "amount", 0)))).isEmpty();
    }

    @Test
    void aPointsFeeRefundIsARefundFoundByItsTransaction() {
        UUID transaction = UUID.randomUUID();
        Optional<Refund> refund = RefundNotificationRules.refundOf(event(IntegrationEvents.LOYALTY_POINTS_LEDGER_COMMITTED,
                Map.of("transactionId", transaction.toString(), "cause", "BOOKING_FEE_REFUND", "appliedAmount", 10_000)));

        assertThat(refund).contains(new Refund(RefundedAs.POINTS, null, transaction, "10000"));
    }

    @Test
    void otherPointsMovementsGiveNothing() {
        for (String cause : new String[] {"BOOKING_FEE_PAYMENT", "ATTENDED_COMPLETION", "ADMIN", "REFERRAL_SIGNUP"}) {
            assertThat(RefundNotificationRules.refundOf(event(IntegrationEvents.LOYALTY_POINTS_LEDGER_COMMITTED,
                    Map.of("transactionId", UUID.randomUUID().toString(), "cause", cause, "appliedAmount", 100))))
                    .as(cause).isEmpty();
        }
    }

    /** PCR-009, PCR-010, PO 19 Nov 2026: the money never leaves the app - no copy mentions a card. */
    @Test
    void refundCopyNeverMentionsCard() {
        Properties english = new NotificationTemplates().all().get(Language.EN);
        for (String code : new String[] {"OPERATIONAL_REFUND_CONFIRMED", "BOOKING_FEE_REFUND_CONFIRMED"}) {
            for (String part : new String[] {"title", "body"}) {
                String text = english.getProperty(code + ".push." + part);
                assertThat(text).as(code + "." + part).isNotBlank();
                assertThat(text.toLowerCase()).as(code + "." + part).doesNotContain("card");
            }
            assertThat(english.getProperty(code + ".push.body")).contains("E7GZ Wallet").contains("Points");
        }
    }

    private static OutboxEvent event(String type, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("recipientUserId", UUID.randomUUID().toString());
        return new OutboxEvent(1, type, "LOYALTY", UUID.randomUUID(), JSON.valueToTree(payload), UUID.randomUUID(),
                Instant.EPOCH, 0);
    }
}
