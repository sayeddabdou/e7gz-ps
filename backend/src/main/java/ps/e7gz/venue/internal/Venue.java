package ps.e7gz.venue.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.geo.Coordinates;

/** One {@code venue.venue} row with its activities ({@code venue.venue_activity}). */
public record Venue(UUID id, UUID areaId, String name, String address, Coordinates location, String phone,
                    String timezone, MarketplaceStatus marketplaceStatus, List<Activity> activities) {

    /** Columns for {@link #map}; the location is read back as WGS 84 latitude / longitude. */
    static final String SELECT = """
            SELECT v.id, v.area_id, v.name, v.address, v.phone, v.timezone, v.marketplace_status,
                   %s,
                   ARRAY(SELECT a.activity FROM venue.venue_activity a WHERE a.venue_id = v.id ORDER BY a.activity)
                       AS activities
              FROM venue.venue v""".formatted(Coordinates.sqlColumns("v.location", "location"));

    static Venue map(ResultSet rs, int row) throws SQLException {
        Array activities = rs.getArray("activities");
        return new Venue(rs.getObject("id", UUID.class), rs.getObject("area_id", UUID.class), rs.getString("name"),
                rs.getString("address"),
                new Coordinates(rs.getBigDecimal("location_lat"), rs.getBigDecimal("location_lng")),
                rs.getString("phone"),
                rs.getString("timezone"), MarketplaceStatus.valueOf(rs.getString("marketplace_status")),
                Arrays.stream((Object[]) activities.getArray()).map(a -> Activity.valueOf((String) a)).toList());
    }
}
