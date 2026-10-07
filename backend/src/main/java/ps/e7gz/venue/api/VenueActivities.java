package ps.e7gz.venue.api;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: the activities a venue offers ({@code venue.venue_activity}), which the Platform Admin manages
 * (FR-ADM-02). Pricing reads it so a price for an activity the venue does not offer is a field error, not the
 * {@code fk_default_price_venue_activity} refusal (E7-27). The Nearest Availability rebuild lists every pair (E7-33).
 */
public interface VenueActivities {

    /** On the command's own transaction; empty when the venue does not exist. */
    Set<Activity> offered(CommandContext command, UUID venueId);

    /** Every venue / activity pair of any venue, ordered by venue then activity, for a read outside a command. */
    List<Offering> all();

    record Offering(UUID venueId, Activity activity) {

        public Offering {
            Objects.requireNonNull(venueId, "venueId");
            Objects.requireNonNull(activity, "activity");
        }
    }
}
