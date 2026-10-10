package ps.e7gz.booking.internal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.PlayMode;

/**
 * What the customer asks for (F-04): a Resource, a play mode (PlayStation only), a start and a nominal duration. The
 * duration is checked by conditions 13-14, not here. The customer is the authenticated caller: the route takes it from
 * the access token, never from the body.
 */
public record OnlineBookingRequest(UUID customerId, UUID resourceId, PlayMode playMode, Instant startAt,
                                   int durationMinutes) {

    public OnlineBookingRequest {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(startAt, "startAt");
    }
}
