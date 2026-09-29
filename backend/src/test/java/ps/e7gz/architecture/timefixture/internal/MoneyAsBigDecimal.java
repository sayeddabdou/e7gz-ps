package ps.e7gz.architecture.timefixture.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class MoneyAsBigDecimal {
    BigDecimal price = new BigDecimal("150.00");

    BigDecimal total(int quantity) {
        return price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }
}
