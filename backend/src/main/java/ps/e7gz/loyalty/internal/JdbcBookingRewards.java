package ps.e7gz.loyalty.internal;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.loyalty.api.BookingRewards;

/**
 * {@code loyalty.begin_command} and {@code loyalty.reconcile_booking} on the command's transaction: the producer
 * functions are the only writers of {@code loyalty.reward_application} and {@code loyalty.points_transaction}
 * (ADR-026).
 */
@Component
class JdbcBookingRewards implements BookingRewards {

    static final String RECONCILE = "SELECT loyalty.reconcile_booking(:booking)";

    @Override
    public UUID reconcile(CommandContext command, UUID customerId, UUID bookingId) {
        Objects.requireNonNull(command, "command");
        command.jdbc().sql(JdbcBookingFeeRefunds.BEGIN)
                .param("user", Objects.requireNonNull(customerId, "customerId"))
                .param("correlation", CorrelationId.require())
                .query().singleRow();
        return command.jdbc().sql(RECONCILE)
                .param("booking", Objects.requireNonNull(bookingId, "bookingId"))
                .query(UUID.class).single();
    }
}
