package ps.e7gz.loyalty.internal;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.loyalty.api.BookingFeeRefunds;

/**
 * {@code loyalty.begin_command} and {@code loyalty.record_refund} on the command's transaction: the producer functions
 * are the only writers of {@code loyalty.booking_fee_refund} (ADR-026). No admin service-loss assessment: the reasons
 * a venue answer ends in are never ADMIN_*.
 */
@Component
class JdbcBookingFeeRefunds implements BookingFeeRefunds {

    static final String BEGIN = "SELECT loyalty.begin_command(ARRAY[CAST(:user AS uuid)], :correlation)";
    static final String RECORD = "SELECT loyalty.record_refund(:booking, :history, NULL)";

    @Override
    public UUID recordDecision(CommandContext command, UUID customerId, UUID bookingId, UUID terminalHistoryId) {
        Objects.requireNonNull(command, "command");
        command.jdbc().sql(BEGIN)
                .param("user", Objects.requireNonNull(customerId, "customerId"))
                .param("correlation", CorrelationId.require())
                .query().singleRow();
        return command.jdbc().sql(RECORD)
                .param("booking", Objects.requireNonNull(bookingId, "bookingId"))
                .param("history", Objects.requireNonNull(terminalHistoryId, "terminalHistoryId"))
                .query(UUID.class).single();
    }
}
