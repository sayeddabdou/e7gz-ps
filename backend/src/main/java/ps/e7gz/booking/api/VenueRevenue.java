package ps.e7gz.booking.api;

import java.time.LocalDate;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.money.Money;

/**
 * The Venue's booking revenue (FR-VEN-36, arch 09 section 4.1), built once for the Overview (FR-VEN-35, E7-115) and the
 * Revenue page (E7-104). Only COMPLETED bookings count, online and manual (PO decision 6 Oct 2026); CONFIRMED, ACTIVE
 * and every cancelled, rejected or no-show booking count nothing. Aggregated on the stored {@code business_date} (arch
 * 04 section 4.3), never on a calendar date of {@code start_at}. No payment method, cash/card split or reconciliation
 * figure (FR-VEN-36). Tournament revenue joins in Sprint 6.
 */
public interface VenueRevenue {

    /**
     * The venue price ({@code venue_price_total}, without the platform Booking Fee) of the Venue's COMPLETED bookings
     * whose business date is in {@code [from, to]}, both inclusive; {@link Money#ZERO} when there is none. Reads on the
     * command's transaction.
     */
    Money completed(CommandContext command, UUID venueId, LocalDate from, LocalDate to);
}
