package ps.e7gz.venue.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: the two venue states a new marketplace activity depends on (category A, ADR-022) - the Venue Marketplace
 * Status and the shared Venue Account status - and the Venue's Area, whose status the Geography module answers. Added
 * for online booking creation (E7-46, FR-BOK-11 conditions 5-7; PO decision 9 Oct 2026). Booking reads them after
 * {@code begin_command} has locked the Venue and its account, so they cannot change before the command commits.
 */
public interface VenueEligibility {

    /** On the command's own transaction; empty when the Venue does not exist. */
    Optional<Eligibility> find(CommandContext command, UUID venueId);

    /**
     * @param accountStatus the shared {@code iam.venue_account} status; {@code null} when the Venue has no account yet,
     *                      which is never {@code ENABLED}
     */
    record Eligibility(UUID venueId, UUID areaId, MarketplaceStatus marketplaceStatus,
                       VenueAccountStatus accountStatus) {

        public Eligibility {
            Objects.requireNonNull(venueId, "venueId");
            Objects.requireNonNull(areaId, "areaId");
            Objects.requireNonNull(marketplaceStatus, "marketplaceStatus");
        }
    }
}
