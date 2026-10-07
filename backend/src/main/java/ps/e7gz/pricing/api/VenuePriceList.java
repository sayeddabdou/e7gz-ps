package ps.e7gz.pricing.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.money.Money;

/**
 * Read-only: a venue's default hourly prices of one activity ({@code pricing.venue_default_price}, FR-VEN-43,
 * FR-VEN-44), for customer Venue Details (FR-DIS-11, E7-34). A room's override is the price of that room, not of the
 * venue, so it is not listed here; {@link HourlyPrices} still applies it to every booking.
 */
public interface VenuePriceList {

    /** By console then play mode (PlayStation: up to four rows; Billiards and Ping Pong: one rate). */
    List<HourlyRate> defaults(UUID venueId, Activity activity);

    /** {@code consoleType} and {@code playMode} are set exactly for PlayStation ({@code ck_price_dimensions}). */
    record HourlyRate(ConsoleType consoleType, PlayMode playMode, Money hourlyPrice) {
        public HourlyRate {
            Objects.requireNonNull(hourlyPrice, "hourlyPrice");
        }
    }
}
