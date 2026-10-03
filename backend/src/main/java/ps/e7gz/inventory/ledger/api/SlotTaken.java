package ps.e7gz.inventory.ledger.api;

import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;

/**
 * The Booking slot is already held by an active reservation: the loser of a race, or a stale availability read,
 * fails cleanly with {@link ApiErrorCode#SLOT_NO_LONGER_AVAILABLE} ({@code FR-AVL-11}, ADR-003).
 *
 * <p>Thrown by {@link ReservationLedger#create} for exactly two database refusals: {@code 23P01} on
 * {@code ex_no_double_booking} when the attempted row is a {@code BOOKING}, and {@code P0001} with guard identifier
 * {@code gd_reservation_conflict_booking}. Every other database failure propagates unchanged, including
 * {@code ex_no_double_booking} on a hold row (E7-30 carry-forward from the E7-29 review).
 */
public class SlotTaken extends ApiException {

    public SlotTaken() {
        super(ApiErrorCode.SLOT_NO_LONGER_AVAILABLE);
    }
}
