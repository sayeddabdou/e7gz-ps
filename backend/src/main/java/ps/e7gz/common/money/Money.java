package ps.e7gz.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An EGP amount: {@code BigDecimal} at scale 2, rounded {@code HALF_UP} (NFR-25, ADR-017). EGP is the only currency
 * (arch 09 §1.2), so it is implied and not stored per value.
 *
 * <p>Every value is normalised to scale 2 on creation, so {@code equals} and {@code hashCode} are value-based:
 * {@code Money.of("1.5").equals(Money.of("1.50"))}. A total is the sum of already-rounded line items (NFR-25): round
 * each line with {@link #times}, then add. There is deliberately no {@code double} factory ({@code new BigDecimal(double)}
 * is banned by an ArchUnit rule); on the wire money is a {@code MoneyView}.
 */
public record Money(BigDecimal amount) implements Comparable<Money> {

    public static final String CURRENCY = "EGP";
    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final Money ZERO = new Money(BigDecimal.ZERO);

    public Money {
        amount = Objects.requireNonNull(amount, "amount").setScale(SCALE, ROUNDING);
    }

    /** From a decimal string such as {@code "150.00"} or {@code "12.345"} (rounded HALF_UP to {@code "12.35"}). */
    public static Money of(String amount) {
        return new Money(new BigDecimal(Objects.requireNonNull(amount, "amount")));
    }

    public static Money of(BigDecimal amount) {
        return new Money(amount);
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    /** One line item: this price times a quantity, rounded once to scale 2 HALF_UP. */
    public Money times(BigDecimal factor) {
        return new Money(amount.multiply(Objects.requireNonNull(factor, "factor")));
    }

    public Money times(long quantity) {
        return new Money(amount.multiply(BigDecimal.valueOf(quantity)));
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    /** Always two decimal places, never exponent notation: {@code "150.00"}. */
    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
