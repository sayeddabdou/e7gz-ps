package ps.e7gz.common.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import ps.e7gz.common.geo.Coordinates;

/** WGS 84 coordinates (PostGIS {@code geography}, ADR-019). */
public record GeoPoint(
        @NotNull @DecimalMin("-90") @DecimalMax("90") @Schema(example = "30.0561") Double lat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") @Schema(example = "31.3301") Double lng) {

    /** The decimal coordinates module code works with; through the decimal text, never the binary double. */
    public Coordinates toCoordinates() {
        return new Coordinates(new BigDecimal(lat.toString()), new BigDecimal(lng.toString()));
    }

    /** Null in, null out (an area may have no centroid). */
    public static GeoPoint of(Coordinates coordinates) {
        return coordinates == null ? null
                : new GeoPoint(coordinates.lat().doubleValue(), coordinates.lng().doubleValue());
    }
}
