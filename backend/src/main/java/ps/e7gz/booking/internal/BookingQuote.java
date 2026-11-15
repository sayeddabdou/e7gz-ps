package ps.e7gz.booking.internal;

import java.util.Objects;
import ps.e7gz.loyalty.api.BookingFeePayments.FeePayment;

/**
 * A Customer's quote: the amounts a booking created now would store, and how its Booking Fee would be paid with the
 * Customer's Points and Wallet now (PCR-010 item 6). Neither is a promise; the created booking's own are authoritative.
 */
public record BookingQuote(BookingAmounts amounts, FeePayment feePayment) {

    public BookingQuote {
        Objects.requireNonNull(amounts, "amounts");
        Objects.requireNonNull(feePayment, "feePayment");
    }
}
