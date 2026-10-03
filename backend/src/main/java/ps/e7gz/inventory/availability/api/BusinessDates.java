package ps.e7gz.inventory.availability.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * The canonical business date, the revenue-day key every Booking and Tournament stores (arch 04 section 4.3,
 * {@code FR-AVL-14}). Exactly two rules:
 * <ol>
 *   <li>an instant inside one of the Venue's materialized Operating Sessions belongs to that session's
 *       {@code operating_day};</li>
 *   <li>an instant outside every session belongs to its {@link #FALLBACK_ZONE} calendar date.</li>
 * </ol>
 * Never null; no cutoff hour, no third rule, and the weekly opening-hours template is never consulted.
 */
public interface BusinessDates {

    /** The calendar of rule 2. */
    ZoneId FALLBACK_ZONE = ZoneId.of("Africa/Cairo");

    /**
     * The business date of something starting at {@code startAt} at the Venue, for the creating command. Reads on the
     * command's own transaction; no transaction is opened here.
     */
    LocalDate businessDate(CommandContext command, UUID venueId, Instant startAt);

    /** The Venue's business date now, the instant taken from the injected {@code Clock}. */
    LocalDate currentBusinessDate(UUID venueId);
}
