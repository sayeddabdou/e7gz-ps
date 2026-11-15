package ps.e7gz.config.api;

import java.time.Instant;
import java.util.Objects;
import ps.e7gz.common.codes.BookingFeeMode;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.money.Money;

/**
 * Read-only: the Booking Fee of a booking created at an instant (Product v5.0 §5.1, FR-ADM-10, FR-PTS-13 as amended
 * by PCR-010; EGP 0 per booking at launch). Since PCR-010 the Platform Admin charges it per booking
 * ({@code booking.fee}) or per hour ({@code booking.fee_per_hour} × nominal minutes ÷ 60, half-up to 2 places), as
 * {@code booking.fee_mode} says. Online booking creation snapshots it and the quote shows it (E7-46, PO decision 9 Oct
 * 2026). The value is the database's {@code config.booking_fee_for(nominalMinutes, at)} - the same function
 * {@code tr_21_booking_online_intake} compares the stored snapshot with - never a value held in Java.
 */
public interface BookingFees {

    /**
     * The fee of a booking of {@code nominalMinutes} (FR-BOK-07: nominal, never elapsed) created at {@code at}, on the
     * command's own transaction.
     *
     * @throws IllegalStateException when the configured amount has more than two decimal places: it could not be
     *                               stored as an EGP amount unchanged, and the database would refuse the snapshot
     */
    BookingFee forBooking(CommandContext command, int nominalMinutes, Instant at);

    /**
     * @param rate   the configured amount the mode uses: the whole fee (PER_BOOKING) or the fee of one hour (PER_HOUR)
     * @param amount the booking's fee
     */
    record BookingFee(BookingFeeMode mode, Money rate, Money amount) {

        public BookingFee {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(rate, "rate");
            Objects.requireNonNull(amount, "amount");
        }
    }
}
