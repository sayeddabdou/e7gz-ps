package ps.e7gz.common.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import ps.e7gz.common.money.Money;

/** Money on the wire: a decimal string with exactly two places plus the currency — never a float (arch 09 §1.2). */
@Schema(name = "Money")
public record MoneyView(
        @NotNull @Pattern(regexp = "^\\d+\\.\\d{2}$") @Schema(example = "150.00") String amount,
        @NotNull Currency currency) {

    @Schema(enumAsRef = true)
    public enum Currency {
        EGP
    }

    /** The wire form of a {@link Money}: its scale-2 plain string and EGP. */
    public static MoneyView of(Money money) {
        return new MoneyView(money.toString(), Currency.EGP);
    }

    /** Back to a {@link Money}; only EGP exists (arch 09 §1.2). */
    public Money toMoney() {
        if (currency != Currency.EGP) {
            throw new IllegalArgumentException("Unsupported currency " + currency);
        }
        return Money.of(amount);
    }
}
