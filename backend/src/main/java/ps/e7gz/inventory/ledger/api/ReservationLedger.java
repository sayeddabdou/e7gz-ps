package ps.e7gz.inventory.ledger.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * The {@code inventory.resource_reservation} ledger (arch 05 section 3, ADR-003). Every write runs on the caller's
 * command transaction ({@link CommandContext#jdbc()}), so it commits or rolls back with the command; the ledger opens
 * no transaction of its own.
 *
 * <p>A row is inserted active and released once. Its Resource, owner and planned interval never change after insert;
 * a released row is never re-activated (arch 05 section 3.2). Overlap is decided by the database, not here.
 */
public interface ReservationLedger {

    /**
     * Inserts an active row with {@code actual_reservation_start} = {@link CommandContext#commandNow()} and returns its
     * id. Publishes {@code ReservationHeld} in the same transaction (E7-46), so the caller runs with a correlation ID
     * open, as every request and job does.
     *
     * @throws SlotTaken when the row is a {@code BOOKING}, an active reservation already holds an overlapping
     *                   interval on the Resource and the database reports it as a Booking slot conflict (see
     *                   {@link SlotTaken}). For a hold, the cross-class guard normally locks the Resource and refuses
     *                   first with its type-specific {@code P0001}; a raw {@code ex_no_double_booking} on a hold means
     *                   the guard was bypassed (a defect) and propagates unchanged, never as {@link SlotTaken} (PO
     *                   decision 3 Oct 2026).
     */
    UUID create(CommandContext command, NewReservation reservation);

    /**
     * Releases an active row: {@code active_flag = false}, {@code actual_reservation_release_at = releaseAt},
     * {@code release_reason = releaseReason}. Nothing else changes; the planned interval stays as inserted.
     *
     * Publishes {@code ReservationReleased {reservationId, resourceId, venueId, period}} in the same transaction when a
     * row is released (arch 08 section 3.2; E7-47), the counterpart of {@code ReservationHeld}, so the projection
     * consumer refreshes the venue at once; the caller runs with a correlation ID open.
     *
     * @return {@code true} if the row was active and is now released; {@code false} if there is no such row or it was
     *         already released (its recorded release facts are left as they are, and nothing is published)
     */
    boolean release(CommandContext command, UUID reservationId, Instant releaseAt, String releaseReason);

    /**
     * The active row of {@code owner}, read on the command's own transaction (E7-47: the venue's answer to a PENDING
     * Booking revalidates and releases the Booking's own reservation). Empty when the owner holds none.
     *
     * @throws IllegalStateException when the owner holds more than one active row of its type - an integrity defect
     */
    Optional<ActiveReservation> activeOf(CommandContext command, ReservationOwner owner);

    /**
     * Check 8 of the availability predicate (arch 05 section 2.2, {@code FR-AVL-03}): whether an active
     * {@code BOOKING}, {@code LATE_CONFIRM_HOLD}, {@code TOURNAMENT_APPROVAL_HOLD} or {@code TOURNAMENT_HOLD} row of
     * the Resource has a planned interval overlapping {@code [start, end)}. A released row never blocks; its planned
     * interval stays as history. {@code MAINTENANCE} and {@code CLOSURE} rows are not counted: the predicate reads
     * Maintenance and Closures from Operations (checks 6-7). Reads on the command's own transaction; {@code end} must be
     * after {@code start}.
     */
    boolean hasBlockingOverlap(CommandContext command, UUID resourceId, Instant start, Instant end);
}
