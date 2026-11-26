package ps.e7gz.loyalty.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.notification.api.RefundedBookings;

/** The notification module's read of a Points refund's booking (E7-80): its {@code booking_fee_refund} decision. */
@Component
class JdbcRefundedBookings implements RefundedBookings {

    static final String BOOKING_OF_POINTS_REFUND = """
            SELECT f.booking_id FROM loyalty.booking_fee_refund f WHERE f.points_transaction_id = :tx
            """;

    @Override
    public Optional<UUID> bookingOfPointsRefund(CommandContext command, UUID pointsTransactionId) {
        return command.jdbc().sql(BOOKING_OF_POINTS_REFUND).param("tx", pointsTransactionId).query(UUID.class)
                .optional();
    }
}
