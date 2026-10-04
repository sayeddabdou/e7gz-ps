package ps.e7gz.venue.api;

import java.util.UUID;

/**
 * Read-only: the venue a venue account belongs to (each venue has one shared account, FR-VEN-01). Added for the
 * Dashboard resource endpoints (E7-26), which are addressed by the signed-in account, not by a venue id.
 */
public interface VenueAccountVenues {

    /**
     * The account's venue.
     *
     * @throws ps.e7gz.common.error.ApiException {@code NOT_FOUND} when the account does not exist
     */
    UUID venueOf(UUID venueAccountId);
}
