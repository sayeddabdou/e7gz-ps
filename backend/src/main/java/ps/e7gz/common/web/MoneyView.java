package ps.e7gz.common.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Money on the wire: a decimal string with exactly two places plus the currency — never a float (arch 09 §1.2). */
@Schema(name = "Money")
public record MoneyView(
        @NotNull @Pattern(regexp = "^\\d+\\.\\d{2}$") @Schema(example = "150.00") String amount,
        @NotNull Currency currency) {

    @Schema(enumAsRef = true)
    public enum Currency {
        EGP
    }
}
