package ps.e7gz.pricing.api;

import java.math.BigDecimal;
import java.util.Objects;
import ps.e7gz.common.money.Money;

/**
 * The one price formula (FR-BOK-06, NFR-25, ADR-017): a line item is {@code hourlyPrice × nominalMinutes ÷ 60},
 * rounded HALF_UP to 2 decimals once; totals add rounded line items. It is the Java side of
 * {@code ck_booking_venue_price_math} ({@code ROUND(hourly_price_snapshot * nominal_booked_minutes / 60.0, 2)}).
 * Price uses the nominal minutes only, never the real elapsed time, so a DST change never moves a price (FR-BOK-07).
 */
public final class PriceCalculator {

    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);

    private PriceCalculator() {
    }

    /**
     * One line item. The product is exact; the division rounds once, straight to scale 2 HALF_UP.
     *
     * @throws IllegalArgumentException for a negative price or a negative number of minutes
     */
    public static Money linePrice(Money hourlyPrice, int nominalMinutes) {
        Objects.requireNonNull(hourlyPrice, "hourlyPrice");
        if (hourlyPrice.isNegative() || nominalMinutes < 0) {
            throw new IllegalArgumentException("Price and minutes must not be negative");
        }
        return Money.of(hourlyPrice.amount().multiply(BigDecimal.valueOf(nominalMinutes))
                .divide(MINUTES_PER_HOUR, Money.SCALE, Money.ROUNDING));
    }
}
