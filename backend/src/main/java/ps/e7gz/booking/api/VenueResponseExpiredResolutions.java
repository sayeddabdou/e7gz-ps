package ps.e7gz.booking.api;

import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * T6 (arch 08 section 1, FR-BOK-23, Spec v5 section 24.3; E7-118): a booking still VENUE_RESPONSE_EXPIRED at its
 * original {@code startAt} is CANCELLED there under one reason. Every PENDING -> VENUE_RESPONSE_EXPIRED transition
 * schedules it in the same command - the venue response expiry (T1, E7-47) inside Booking, and the Venue Account
 * disable (FR-VEN-09, E7-78) through this entry point - so no expired request is left without its resolution.
 */
public interface VenueResponseExpiredResolutions {

    /**
     * Schedules the booking's T6 at its stored {@code startAt}, on the caller's transaction, which has just moved it to
     * VENUE_RESPONSE_EXPIRED. Idempotent: a booking already holding its T6 keeps it. The caller holds the booking's
     * Customer, Venue and Resource locks.
     *
     * @throws IllegalStateException when the booking does not exist or is not VENUE_RESPONSE_EXPIRED
     */
    void scheduleAtStart(CommandContext command, UUID bookingId);
}
