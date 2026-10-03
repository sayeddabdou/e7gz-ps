package ps.e7gz.inventory.ledger.api;

import java.time.Instant;
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
     * id.
     *
     * @throws SlotTaken when an active reservation already holds an overlapping interval on the Resource and the
     *                   database reports it as a Booking slot conflict (see {@link SlotTaken})
     */
    UUID create(CommandContext command, NewReservation reservation);

    /**
     * Releases an active row: {@code active_flag = false}, {@code actual_reservation_release_at = releaseAt},
     * {@code release_reason = releaseReason}. Nothing else changes; the planned interval stays as inserted.
     *
     * @return {@code true} if the row was active and is now released; {@code false} if there is no such row or it was
     *         already released (its recorded release facts are left as they are)
     */
    boolean release(CommandContext command, UUID reservationId, Instant releaseAt, String releaseReason);
}
