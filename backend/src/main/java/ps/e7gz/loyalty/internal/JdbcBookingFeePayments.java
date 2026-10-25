package ps.e7gz.loyalty.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.money.Money;
import ps.e7gz.loyalty.api.BookingFeePayments;

/**
 * {@code loyalty.begin_command} and {@code loyalty.pay_booking_fee} on the command's transaction: the producer is the
 * only writer of a Points or Wallet payment (ADR-026) and decides it itself - whether there is a fee to pay, what the
 * Points cover and whether the Wallet covers the rest - so this code does not re-check any of it. The quote reads the
 * same rule through {@code loyalty.preview_fee_payment}.
 */
@Component
class JdbcBookingFeePayments implements BookingFeePayments {

    static final String PAY = "SELECT p.points_transaction_id FROM loyalty.pay_booking_fee(:booking) p";

    static final String PREVIEW = """
            SELECT p.fee_amount, p.points_used, p.paid_by_points, p.paid_by_wallet
              FROM loyalty.preview_fee_payment(:user, :minutes, CAST(:at AS timestamptz)) p""";

    /** The Wallet's part is the booking's WALLET collection receipt; the Points' part is its payment row. */
    static final String PAID = """
            SELECT b.booking_fee_snapshot AS fee_amount, COALESCE(p.points_used, 0) AS points_used,
                   COALESCE(p.amount_paid, 0) AS paid_by_points, COALESCE(r.amount, 0) AS paid_by_wallet
              FROM booking.booking b
              LEFT JOIN loyalty.booking_fee_points_payment p ON p.booking_id = b.id
              LEFT JOIN loyalty.booking_fee_receipt r ON r.booking_id = b.id AND r.provider = 'WALLET'
             WHERE b.id = :booking""";

    @Override
    public FeePayment pay(CommandContext command, UUID customerId, UUID bookingId) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(bookingId, "bookingId");
        command.jdbc().sql(JdbcBookingFeeRefunds.BEGIN)
                .param("user", Objects.requireNonNull(customerId, "customerId"))
                .param("correlation", CorrelationId.require())
                .query().singleRow();
        command.jdbc().sql(PAY).param("booking", bookingId).query().singleRow();
        return paid(command, bookingId);
    }

    @Override
    public FeePayment preview(CommandContext command, UUID customerId, int nominalMinutes, Instant at) {
        Objects.requireNonNull(command, "command");
        return command.jdbc().sql(PREVIEW)
                .param("user", Objects.requireNonNull(customerId, "customerId"))
                .param("minutes", nominalMinutes)
                .param("at", OffsetDateTime.ofInstant(Objects.requireNonNull(at, "at"), ZoneOffset.UTC))
                .query((rs, n) -> payment(rs)).single();
    }

    @Override
    public FeePayment paid(CommandContext command, UUID bookingId) {
        Objects.requireNonNull(command, "command");
        return command.jdbc().sql(PAID).param("booking", Objects.requireNonNull(bookingId, "bookingId"))
                .query((rs, n) -> payment(rs)).single();
    }

    private static FeePayment payment(ResultSet rs) throws SQLException {
        Money fee = Money.of(rs.getBigDecimal("fee_amount"));
        Money byPoints = Money.of(rs.getBigDecimal("paid_by_points"));
        Money byWallet = Money.of(rs.getBigDecimal("paid_by_wallet"));
        return new FeePayment(rs.getInt("points_used"), byPoints, byWallet, fee.minus(byPoints).minus(byWallet));
    }
}
