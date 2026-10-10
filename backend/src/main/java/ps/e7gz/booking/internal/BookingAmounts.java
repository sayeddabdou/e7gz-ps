package ps.e7gz.booking.internal;

import java.util.Objects;
import ps.e7gz.common.money.Money;

/**
 * The amounts of an online booking as {@link BookingPricing} computes them, every one at scale 2 ({@link Money}):
 * {@code venuePriceTotal = round(hourlyPrice × nominalBookedMinutes ÷ 60)} and
 * {@code totalAmount = venuePriceTotal + bookingFee} ({@code ck_booking_venue_price_math}, {@code ck_booking_total_math}).
 */
public record BookingAmounts(Money hourlyPrice, int nominalBookedMinutes, Money venuePriceTotal, Money bookingFee,
                             Money totalAmount) {

    public BookingAmounts {
        Objects.requireNonNull(hourlyPrice, "hourlyPrice");
        Objects.requireNonNull(venuePriceTotal, "venuePriceTotal");
        Objects.requireNonNull(bookingFee, "bookingFee");
        Objects.requireNonNull(totalAmount, "totalAmount");
    }
}
