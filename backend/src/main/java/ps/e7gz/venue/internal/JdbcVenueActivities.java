package ps.e7gz.venue.internal;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.venue.api.VenueActivities;

/** {@code venue.venue_activity} on the command's transaction, or on the app connection for the full list. */
@Component
class JdbcVenueActivities implements VenueActivities {

    private final JdbcClient jdbc;

    JdbcVenueActivities(DataSource appDataSource) {
        this.jdbc = JdbcClient.create(appDataSource);
    }

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

    @Override
    public List<Offering> all() {
        return jdbc.sql("SELECT venue_id, activity FROM venue.venue_activity ORDER BY venue_id, activity")
                .query((rs, n) -> new Offering(rs.getObject("venue_id", UUID.class),
                        Activity.valueOf(rs.getString("activity"))))
                .list();
    }
}
