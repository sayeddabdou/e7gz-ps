package ps.e7gz.booking.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.VenueRevenue;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.money.Money;

/** {@link VenueRevenue} over {@code booking.booking}: one SUM on the command's transaction. */
@Component
class JdbcVenueRevenue implements VenueRevenue {

    /** Every source of {@code booking.booking} counts (ONLINE and MANUAL); only the status decides. */
    static final String COMPLETED_REVENUE = """
            SELECT COALESCE(SUM(b.venue_price_total), 0)
              FROM booking.booking b
             WHERE b.venue_id = :venueId AND b.status = 'COMPLETED'
               AND b.business_date BETWEEN :from AND :to
            """;

    @Override
    public Money completed(CommandContext command, UUID venueId, LocalDate from, LocalDate to) {
        Objects.requireNonNull(venueId, "venueId");
        if (to.isBefore(Objects.requireNonNull(from, "from"))) {
            throw new IllegalArgumentException("to must not be before from: " + from + " > " + to);
        }
        BigDecimal sum = command.jdbc().sql(COMPLETED_REVENUE)
                .param("venueId", venueId).param("from", from).param("to", to)
                .query(BigDecimal.class).single();
        return Money.of(sum);
    }
}
