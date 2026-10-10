package ps.e7gz.config.api;

import java.time.Instant;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.money.Money;

/**
 * Read-only: the Global Booking Fee in force at an instant (Product v5.0 §5.1, FR-ADM-10, FR-PTS-13; key
 * {@code booking.fee}, EGP 0 at launch). Online booking creation snapshots it and the quote shows it (E7-46, PO
 * decision 9 Oct 2026). The value is the database's {@code config.effective_numeric('booking.fee', at)} - the same
 * function {@code tr_21_booking_online_intake} compares the stored snapshot with - never a value held in Java.
 */
public interface BookingFees {

    /**
     * The fee effective at {@code at}, on the command's own transaction.
     *
     * @throws IllegalStateException when the configured value has more than two decimal places: it could not be stored
     *                               as an EGP amount unchanged, and the database would refuse the rounded snapshot
     */
    Money effectiveAt(CommandContext command, Instant at);
}
