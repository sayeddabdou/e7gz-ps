package ps.e7gz.inventory.ledger.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A reservation row to insert: the Resource, its owner and the planned interval {@code [plannedStart, plannedEnd)}.
 * All of these are immutable once inserted (arch 05 section 3.2, {@code FR-AVL-13}). The database checks
 * {@code planned_end > planned_start} ({@code ck_reservation_period}).
 */
public record NewReservation(UUID resourceId, ReservationOwner owner, Instant plannedStart, Instant plannedEnd) {

    public NewReservation {
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(plannedStart, "plannedStart");
        Objects.requireNonNull(plannedEnd, "plannedEnd");
    }
}
