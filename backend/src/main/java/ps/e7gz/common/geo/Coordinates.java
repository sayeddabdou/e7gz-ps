package ps.e7gz.common.geo;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * WGS 84 latitude / longitude as decimals, for the PostGIS {@code geography} columns (ADR-019). Module code carries
 * these instead of {@code double} (NFR-25 keeps floating point out of domain packages). In SQL, {@code ST_MakePoint}
 * takes longitude first: {@link #SQL_POINT}.
 */
public record Coordinates(BigDecimal lat, BigDecimal lng) {

    /** A geography point from the named parameters {@code :lat} and {@code :lng}. */
    public static final String SQL_POINT =
            "ST_SetSRID(ST_MakePoint(CAST(:lng AS double precision), CAST(:lat AS double precision)), 4326)::geography";

    public Coordinates {
        Objects.requireNonNull(lat, "lat");
        Objects.requireNonNull(lng, "lng");
    }

    /** Select-list expressions reading {@code column} back as {@code <alias>_lat}, {@code <alias>_lng}. */
    public static String sqlColumns(String column, String alias) {
        return "ST_Y(%1$s::geometry) AS %2$s_lat, ST_X(%1$s::geometry) AS %2$s_lng".formatted(column, alias);
    }
}
