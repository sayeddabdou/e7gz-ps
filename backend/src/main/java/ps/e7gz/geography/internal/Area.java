package ps.e7gz.geography.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import ps.e7gz.common.geo.Coordinates;

/** One {@code geo.area} row; {@code centroid} is null when the area has none. */
public record Area(UUID id, String name, String nameAr, boolean active, Coordinates centroid) {

    /** Columns for {@link #map}: the centroid is read back as WGS 84 latitude / longitude. */
    static final String COLUMNS = "id, name, name_ar, is_active, " + Coordinates.sqlColumns("centroid", "centroid");

    static Area map(ResultSet rs, int row) throws SQLException {
        BigDecimal lat = rs.getBigDecimal("centroid_lat");
        return new Area(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("name_ar"),
                rs.getBoolean("is_active"),
                lat == null ? null : new Coordinates(lat, rs.getBigDecimal("centroid_lng")));
    }
}
