package ps.e7gz.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Random;
import org.junit.jupiter.api.Test;
import ps.e7gz.common.money.Money;
import ps.e7gz.pricing.api.PriceCalculator;

/** E7-27: {@code round(hourly × minutes / 60, 2)} HALF_UP per line item (FR-BOK-06, NFR-25, ADR-017). */
class PriceCalculatorTest {

    @Test
    void wholeHoursAndHalfHours() {
        assertThat(price("150.00", 60)).isEqualTo("150.00");
        assertThat(price("150.00", 90)).isEqualTo("225.00");
        assertThat(price("120.00", 360)).isEqualTo("720.00");
        assertThat(price("0.00", 120)).isEqualTo("0.00");
    }

    @Test
    void roundsOnceHalfUpAtTheSecondDecimal() {
        assertThat(price("1.01", 90)).isEqualTo("1.52");    // 1.515 exactly -> up
        assertThat(price("0.03", 30)).isEqualTo("0.02");    // 0.015 exactly -> up
        assertThat(price("0.01", 30)).isEqualTo("0.01");    // 0.005 exactly -> up
        assertThat(price("0.01", 20)).isEqualTo("0.00");    // 0.00333… -> down
        assertThat(price("33.33", 90)).isEqualTo("50.00");  // 49.995 exactly -> up
        assertThat(price("10.01", 10)).isEqualTo("1.67");   // 1.668333… -> up
        assertThat(price("10.00", 10)).isEqualTo("1.67");   // 1.666… -> up, not 1.66 (never truncated)
    }

    @Test
    void theLargestPriceForTheLongestBookingStillFits() {
        assertThat(price("9999999999.99", 360)).isEqualTo("59999999999.94");
    }

    @Test
    void refusesNegativeInput() {
        assertThatThrownBy(() -> PriceCalculator.linePrice(Money.of("-1.00"), 60))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PriceCalculator.linePrice(Money.of("1.00"), -30))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Property: for any price with 2 decimals and any number of minutes, the result is the exact value rounded
     * HALF_UP to 2 decimals: within half a piastre of the exact value, and a tie rounds up.
     */
    @Test
    void propertyTheResultIsTheExactValueRoundedHalfUp() {
        Random random = new Random(20261004L);
        BigDecimal half = new BigDecimal("0.005");
        for (int i = 0; i < 100_000; i++) {
            Money hourly = Money.of(BigDecimal.valueOf(random.nextLong(0, 100_000_000L), 2));
            int minutes = random.nextInt(0, 24 * 60 + 1);
            BigDecimal exact = hourly.amount().multiply(BigDecimal.valueOf(minutes))
                    .divide(BigDecimal.valueOf(60), MathContext.DECIMAL128);

            Money line = PriceCalculator.linePrice(hourly, minutes);

            assertThat(line.amount().scale()).isEqualTo(2);
            BigDecimal error = line.amount().subtract(exact);
            assertThat(error.abs()).as("%s × %d", hourly, minutes).isLessThanOrEqualTo(half);
            if (error.abs().compareTo(half) == 0) {
                assertThat(error.signum()).as("a tie rounds up: %s × %d", hourly, minutes).isPositive();
            }
            assertThat(line.amount()).isEqualTo(exact.setScale(2, RoundingMode.HALF_UP));
        }
    }

    private static String price(String hourly, int minutes) {
        return PriceCalculator.linePrice(Money.of(hourly), minutes).toString();
    }
}
