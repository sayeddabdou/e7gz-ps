package ps.e7gz.config.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.BookingFeeMode;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.money.Money;
import ps.e7gz.config.api.BookingFees;

/** {@code config.booking_fee_for(nominalMinutes, at)} on the command's transaction (granted to the app runtime). */
@Component
class JdbcBookingFees implements BookingFees {

    static final String PER_BOOKING_KEY = "booking.fee";
    static final String PER_HOUR_KEY = "booking.fee_per_hour";

    static final String FEE = """
            SELECT f.fee_mode, f.fee_rate, f.fee_amount
              FROM config.booking_fee_for(:minutes, CAST(:at AS timestamptz)) f""";

    @Override
    public BookingFee forBooking(CommandContext command, int nominalMinutes, Instant at) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(at, "at");
        return command.jdbc().sql(FEE)
                .param("minutes", nominalMinutes)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query((rs, n) -> {
                    BookingFeeMode mode = BookingFeeMode.valueOf(rs.getString("fee_mode"));
                    BigDecimal rate = rs.getBigDecimal("fee_rate");
                    if (rate.stripTrailingZeros().scale() > Money.SCALE) {
                        String key = mode == BookingFeeMode.PER_HOUR ? PER_HOUR_KEY : PER_BOOKING_KEY;
                        throw new IllegalStateException(key + " has more than " + Money.SCALE + " decimal places: "
                                + rate);
                    }
                    return new BookingFee(mode, Money.of(rate), Money.of(rs.getBigDecimal("fee_amount")));
                })
                .single();
    }
}
