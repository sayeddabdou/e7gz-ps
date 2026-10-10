package ps.e7gz.config.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.money.Money;
import ps.e7gz.config.api.BookingFees;

/** {@code config.effective_numeric('booking.fee', at)} on the command's transaction (granted to the app runtime). */
@Component
class JdbcBookingFees implements BookingFees {

    static final String KEY = "booking.fee";

    static final String EFFECTIVE = "SELECT config.effective_numeric(:key, CAST(:at AS timestamptz))";

    @Override
    public Money effectiveAt(CommandContext command, Instant at) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(at, "at");
        BigDecimal value = command.jdbc().sql(EFFECTIVE)
                .param("key", KEY)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query(BigDecimal.class).single();
        if (value.stripTrailingZeros().scale() > Money.SCALE) {
            throw new IllegalStateException(KEY + " has more than " + Money.SCALE + " decimal places: " + value);
        }
        return Money.of(value);
    }
}
