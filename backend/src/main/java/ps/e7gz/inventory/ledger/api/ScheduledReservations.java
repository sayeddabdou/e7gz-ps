package ps.e7gz.inventory.ledger.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only: the active commitment rows of Resources over a window, each with its type and owner, for the Venue
 * Dashboard schedule (FR-VEN-37, E7-112). Rows are not merged: every row is one span the schedule shows. The
 * {@code MAINTENANCE} and {@code CLOSURE} guard rows are left out; the schedule reads Maintenance and Closures from
 * Operations, their owner.
 */
public interface ScheduledReservations {

    /**
     * The active ({@code active_flag}, no release) {@code BOOKING}, {@code LATE_CONFIRM_HOLD},
     * {@code TOURNAMENT_APPROVAL_HOLD} and {@code TOURNAMENT_HOLD} rows of {@code resourceIds} whose planned interval
     * overlaps {@code [from, to)}, by Resource, then planned start. Released rows never appear (arch 05 section 3.2);
     * intervals are not clipped. One read outside any command; {@code to} must be after {@code from}.
     */
    List<Row> active(Collection<UUID> resourceIds, Instant from, Instant to);

    /** One active row: its id, Resource, owner and planned interval {@code [plannedStart, plannedEnd)}. */
    record Row(UUID reservationId, UUID resourceId, ReservationOwner owner, Instant plannedStart, Instant plannedEnd) {

        public Row {
            Objects.requireNonNull(reservationId, "reservationId");
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(plannedStart, "plannedStart");
            Objects.requireNonNull(plannedEnd, "plannedEnd");
        }
    }
}
