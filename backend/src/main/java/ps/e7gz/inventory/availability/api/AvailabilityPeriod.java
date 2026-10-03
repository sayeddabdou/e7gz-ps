package ps.e7gz.inventory.availability.api;

import java.time.Instant;
import java.util.Objects;

/**
 * The half-open interval {@code [start, end)} a caller asks about (arch 05 section 2.2). Back-to-back periods do not
 * overlap. Overlap itself is decided by the owner modules' SQL, never here.
 */
public record AvailabilityPeriod(Instant start, Instant end) {

    public AvailabilityPeriod {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start: [" + start + ", " + end + ")");
        }
    }
}
