package ps.e7gz.architecture.timefixture.web;

/** A web DTO: coordinates as Double are fine outside domain packages (compare GeoPoint). */
public record CoordinatesDto(Double lat, Double lng) {
}
