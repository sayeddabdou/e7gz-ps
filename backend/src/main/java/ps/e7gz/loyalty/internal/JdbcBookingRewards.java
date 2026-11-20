package ps.e7gz.loyalty.internal;

import java.util.Comparator;
import java.util.Map;
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

    /** Admin runtime only (security baseline {@code admin_only}). */
    static final String BEGIN_ADMIN =
            "SELECT loyalty.begin_admin_command(ARRAY[CAST(:user AS uuid)], :admin, :correlation)";

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

    @Override
    public UUID reconcileCorrection(CommandContext command, UUID adminId, UUID customerId, UUID bookingId) {
        beginCorrection(command, adminId, customerId);
        return command.jdbc().sql(RECONCILE)
                .param("booking", Objects.requireNonNull(bookingId, "bookingId"))
                .query(UUID.class).single();
    }

    /** {@code loyalty.start_command} returns the open context unchanged when called again with the same arguments. */
    @Override
    public void beginCorrection(CommandContext command, UUID adminId, UUID customerId) {
        Objects.requireNonNull(command, "command");
        command.jdbc().sql(BEGIN_ADMIN)
                .param("user", Objects.requireNonNull(customerId, "customerId"))
                .param("admin", Objects.requireNonNull(adminId, "adminId"))
                .param("correlation", CorrelationId.require())
                .query().singleRow();
    }

    @Override
    public void reconcileAll(CommandContext command, Map<UUID, UUID> bookings) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(bookings, "bookings");
        if (bookings.isEmpty()) {
            return;
        }
        if (!JdbcBookingFeeRefunds.loyaltyCommandOpen(command)) {
            command.jdbc().sql(JdbcBookingFeeRefunds.BEGIN_MANY)
                    .param("users", bookings.values().stream().distinct().sorted().toArray(UUID[]::new))
                    .param("correlation", CorrelationId.require())
                    .query().singleRow();
        }
        bookings.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<UUID, UUID> entry) -> entry.getValue())
                        .thenComparing(Map.Entry::getKey))
                .forEach(entry -> command.jdbc().sql(RECONCILE)
                        .param("booking", entry.getKey())
                        .query(UUID.class).single());
    }
}
