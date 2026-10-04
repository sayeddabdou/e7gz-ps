package ps.e7gz.venue.internal;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.venue.api.VenueActivities;

/** {@code venue.venue_activity} on the command's transaction. */
@Component
class JdbcVenueActivities implements VenueActivities {

    @Override
    public Set<Activity> offered(CommandContext command, UUID venueId) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(venueId, "venueId");
        Set<Activity> offered = EnumSet.noneOf(Activity.class);
        command.jdbc().sql("SELECT activity FROM venue.venue_activity WHERE venue_id = :venue")
                .param("venue", venueId).query(String.class).list()
                .forEach(activity -> offered.add(Activity.valueOf(activity)));
        return offered;
    }
}
