package ps.e7gz.loyalty.internal;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.loyalty.api.BookingFeePayments;

/**
 * {@code loyalty.begin_command} and {@code loyalty.pay_fee_from_wallet} on the command's transaction: the producer is
 * the only writer of a Wallet payment (ADR-026) and decides it itself - whether there is a fee to pay and whether the
 * Wallet covers it - so this code does not re-check either.
 */
@Component
class JdbcBookingFeePayments implements BookingFeePayments {

    static final String PAY = "SELECT loyalty.pay_fee_from_wallet(:booking)";

    @Override
    public Optional<UUID> payFromWallet(CommandContext command, UUID customerId, UUID bookingId) {
        Objects.requireNonNull(command, "command");
        command.jdbc().sql(JdbcBookingFeeRefunds.BEGIN)
                .param("user", Objects.requireNonNull(customerId, "customerId"))
                .param("correlation", CorrelationId.require())
                .query().singleRow();
        return Optional.ofNullable(command.jdbc().sql(PAY)
                .param("booking", Objects.requireNonNull(bookingId, "bookingId"))
                .query(UUID.class).list().getFirst());
    }
}
