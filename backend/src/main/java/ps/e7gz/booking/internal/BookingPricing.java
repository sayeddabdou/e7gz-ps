package ps.e7gz.booking.internal;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.money.Money;
import ps.e7gz.config.api.BookingFees;
import ps.e7gz.pricing.api.HourlyPrices;
import ps.e7gz.pricing.api.PriceCalculator;

/**
 * The amounts of an online booking - the one place both creation and the quote compute them, so the quote is exactly
 * what a booking created now would store (PO decision 5 Oct 2026, E7-39; FR-BOK-06, FR-BOK-07, FR-PTS-13):
 * <ul>
 *   <li>hourly price: {@link HourlyPrices} (room override, else venue default);</li>
 *   <li>venue price: {@code hourlyPrice × nominalMinutes ÷ 60}, half-up to 2 places, once ({@link PriceCalculator});</li>
 *   <li>booking fee: the Booking Fee of the nominal minutes in force at the command instant - per booking, or per
 *       hour since PCR-010 ({@link BookingFees});</li>
 *   <li>total: the sum of the two rounded line items (NFR-25).</li>
 * </ul>
 * Nominal minutes only, never the real elapsed time, so a DST change never moves a price (FR-BOK-07).
 */
@Component
class BookingPricing {

    private final HourlyPrices hourlyPrices;
    private final BookingFees fees;

    BookingPricing(HourlyPrices hourlyPrices, BookingFees fees) {
        this.hourlyPrices = hourlyPrices;
        this.fees = fees;
    }

    /**
     * On the command's transaction, at its instant. Empty when the venue has no price for the Resource's activity,
     * console and play mode (FR-VEN-45) - for creation a failure of condition 20 (PO decision 5 Oct 2026).
     */
    Optional<BookingAmounts> price(CommandContext command, UUID resourceId, PlayMode playMode, int nominalMinutes) {
        Objects.requireNonNull(command, "command");
        return hourlyPrices.resolve(command, resourceId, playMode)
                .map(hourly -> amounts(hourly, nominalMinutes,
                        fees.forBooking(command, nominalMinutes, command.commandNow()).amount()));
    }

    static BookingAmounts amounts(Money hourlyPrice, int nominalMinutes, Money bookingFee) {
        Money venuePriceTotal = PriceCalculator.linePrice(hourlyPrice, nominalMinutes);
        return new BookingAmounts(hourlyPrice, nominalMinutes, venuePriceTotal, bookingFee,
                venuePriceTotal.plus(bookingFee));
    }
}
