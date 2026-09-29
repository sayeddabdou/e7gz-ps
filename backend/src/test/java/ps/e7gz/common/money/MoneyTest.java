package ps.e7gz.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ps.e7gz.common.web.MoneyView;

/** NFR-25 / ADR-017: scale 2, HALF_UP, EGP; value equality; wire form a 2-decimal string, never a JSON number. */
class MoneyTest {

    @ParameterizedTest
    @CsvSource({"12.345, 12.35", "12.344, 12.34", "0.005, 0.01", "-0.005, -0.01", "150, 150.00", "1.5, 1.50"})
    void everyAmountIsScale2HalfUp(String input, String expected) {
        Money money = Money.of(input);
        assertThat(money.amount().scale()).isEqualTo(2);
        assertThat(money.toString()).isEqualTo(expected);
    }

    @Test
    void equalityIsByValueNotByBigDecimalScale() {
        assertThat(Money.of("1.5")).isEqualTo(Money.of("1.50")).hasSameHashCodeAs(Money.of("1.50"));
        assertThat(Money.of(new BigDecimal("2.0"))).isEqualTo(Money.of("2"));
        assertThat(Money.of("2.00").compareTo(Money.of("2"))).isZero();
    }

    @Test
    void aTotalIsTheSumOfRoundedLineItems() {
        // 3 lines of 33.333 each: each line rounds to 33.33, so the total is 99.99 (not round(99.999) = 100.00).
        Money line = Money.of("10.00").times(new BigDecimal("3.3333"));
        assertThat(line).isEqualTo(Money.of("33.33"));
        Money total = List.of(line, line, line).stream().reduce(Money.ZERO, Money::plus);
        assertThat(total).isEqualTo(Money.of("99.99"));
    }

    @Test
    void arithmetic() {
        assertThat(Money.of("150.00").times(3)).isEqualTo(Money.of("450.00"));
        assertThat(Money.of("10.00").minus(Money.of("10.01")).isNegative()).isTrue();
        assertThat(Money.ZERO.isZero()).isTrue();
        assertThat(Money.of("150.00").times(new BigDecimal("0.5"))).isEqualTo(Money.of("75.00"));
    }

    @Test
    void neverNull() {
        assertThatThrownBy(() -> Money.of((String) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Money.of((BigDecimal) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void onTheWireMoneyIsATwoDecimalStringWithEgp() throws Exception {
        String json = new ObjectMapper().writeValueAsString(MoneyView.of(Money.of("150")));
        assertThat(json).isEqualTo("{\"amount\":\"150.00\",\"currency\":\"EGP\"}");
        assertThat(MoneyView.of(Money.of("0.1")).amount()).isEqualTo("0.10");
        assertThat(new MoneyView("99.99", MoneyView.Currency.EGP).toMoney()).isEqualTo(Money.of("99.99"));
    }
}
