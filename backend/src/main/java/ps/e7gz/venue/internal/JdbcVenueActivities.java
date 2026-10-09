package ps.e7gz.venue.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
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
                .query(JdbcVenueActivities::offering).list();
    }

    @Override
    public List<Offering> ofVenue(UUID venueId) {
        Objects.requireNonNull(venueId, "venueId");
        return jdbc.sql("SELECT venue_id, activity FROM venue.venue_activity WHERE venue_id = :venue ORDER BY activity")
                .param("venue", venueId).query(JdbcVenueActivities::offering).list();
    }

    @Override
    public List<Offering> inArea(UUID areaId) {
        Objects.requireNonNull(areaId, "areaId");
        return jdbc.sql("""
                        SELECT a.venue_id, a.activity
                          FROM venue.venue_activity a
                          JOIN venue.venue v ON v.id = a.venue_id
                         WHERE v.area_id = :area
                         ORDER BY a.venue_id, a.activity""")
                .param("area", areaId).query(JdbcVenueActivities::offering).list();
    }

    private static Offering offering(ResultSet rs, int row) throws SQLException {
        return new Offering(rs.getObject("venue_id", UUID.class), Activity.valueOf(rs.getString("activity")));
    }
}
