package ps.e7gz.inventory.ledger.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An active ledger row as its owner sees it: its id (to release it) and its immutable Resource and planned interval
 * {@code [plannedStart, plannedEnd)} (arch 05 section 3.2). Added for the venue's answer to a PENDING Booking (E7-47),
 * which revalidates and releases the Booking's own reservation.
 */
public record ActiveReservation(UUID id, UUID resourceId, Instant plannedStart, Instant plannedEnd) {

    public ActiveReservation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(plannedStart, "plannedStart");
        Objects.requireNonNull(plannedEnd, "plannedEnd");
    }
}
