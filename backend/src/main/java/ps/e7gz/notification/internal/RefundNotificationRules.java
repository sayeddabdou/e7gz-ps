package ps.e7gz.notification.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxEvent;

/**
 * Which refund confirmation a loyalty ledger event gives (Spec v5 section 52.2 "Operational refund confirmation, and
 * full Booking Fee refund confirmation"; E7-80, PO decisions 25 Nov 2026 Q6, R1, R4, R5; PCR-009, PCR-010).
 *
 * <ul>
 *   <li>A refund is told when it is actually given back, so never at EGP 0: a Wallet credit
 *       ({@code LOYALTY_WALLET_LEDGER_COMMITTED} with a {@code refundId} and an amount above 0, written by
 *       {@code loyalty.settle_refund} only once the fee's collection receipt exists) or Points given back
 *       ({@code LOYALTY_POINTS_LEDGER_COMMITTED} with cause {@code BOOKING_FEE_REFUND}). A fee paid partly with Points
 *       and partly from the Wallet gives one confirmation per part (R1).</li>
 *   <li>The code follows the booking's terminal reason (R4): an operational cause -
 *       {@code OPERATIONAL_REFUND_CONFIRMED}; any other full refund - {@code BOOKING_FEE_REFUND_CONFIRMED}.</li>
 *   <li>Never a card: the money stays in the app (PCR-009); the copy says the E7GZ Wallet or Points.</li>
 * </ul>
 */
final class RefundNotificationRules {

    static final String OPERATIONAL = "OPERATIONAL_REFUND_CONFIRMED";
    static final String FEE = "BOOKING_FEE_REFUND_CONFIRMED";

    /** R4 (PO decision 25 Nov 2026). */
    static final Set<BookingTerminalReason> OPERATIONAL_REASONS = EnumSet.of(
            BookingTerminalReason.VENUE_EMERGENCY_CLOSURE, BookingTerminalReason.RESOURCE_EMERGENCY_OUTAGE,
            BookingTerminalReason.RESOURCE_OWNER_REVIEW_REQUIRED, BookingTerminalReason.VENUE_CLOSURE,
            BookingTerminalReason.VENUE_ACCOUNT_DISABLED, BookingTerminalReason.SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR,
            BookingTerminalReason.VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY,
            BookingTerminalReason.RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY,
            BookingTerminalReason.LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY);

    /** Where the refund went: the {@code refundedAs} param. */
    enum RefundedAs {
        WALLET, POINTS
    }

    /**
     * One refund: a Wallet credit names its booking; Points name their transaction, whose booking the loyalty module
     * knows ({@code booking_id} is NULL on a BOOKING_FEE_REFUND points row, {@code ck_points_tx_origin}).
     *
     * @param amount EGP with two decimals, or the Points given back
     */
    record Refund(RefundedAs refundedAs, UUID bookingId, UUID pointsTransactionId, String amount) {
    }

    private RefundNotificationRules() {
    }

    static Set<String> eventTypes() {
        return Set.of(IntegrationEvents.LOYALTY_WALLET_LEDGER_COMMITTED, IntegrationEvents.LOYALTY_POINTS_LEDGER_COMMITTED);
    }

    static String code(BookingTerminalReason reason) {
        return OPERATIONAL_REASONS.contains(reason) ? OPERATIONAL : FEE;
    }

    static Optional<Refund> refundOf(OutboxEvent event) {
        JsonNode payload = event.payload();
        if (IntegrationEvents.LOYALTY_WALLET_LEDGER_COMMITTED.equals(event.eventType())) {
            BigDecimal amount = payload.path("amount").decimalValue();
            if (!payload.path("refundId").isTextual() || amount.signum() <= 0) {
                return Optional.empty();
            }
            return Optional.of(new Refund(RefundedAs.WALLET, event.uuid("bookingId"), null,
                    amount.setScale(2, RoundingMode.HALF_UP).toPlainString()));
        }
        if (IntegrationEvents.LOYALTY_POINTS_LEDGER_COMMITTED.equals(event.eventType())
                && "BOOKING_FEE_REFUND".equals(event.text("cause"))) {
            long points = payload.path("appliedAmount").asLong(0);
            if (points <= 0) {
                return Optional.empty();
            }
            return Optional.of(new Refund(RefundedAs.POINTS, null, event.uuid("transactionId"),
                    Long.toString(points)));
        }
        return Optional.empty();
    }
}
