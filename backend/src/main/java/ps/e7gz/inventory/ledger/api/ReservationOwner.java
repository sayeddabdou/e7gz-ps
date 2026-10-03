package ps.e7gz.inventory.ledger.api;

import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.ReservationType;

/**
 * Who owns a reservation row. One variant per {@link ReservationType}, each carrying exactly the one owner id that
 * {@code ck_reservation_owner_shape} requires for that type, so a row with no owner or two owners cannot be described
 * (arch 05 section 3.2). The database check stays the authority.
 */
public sealed interface ReservationOwner {

    ReservationType type();

    /** The owner id: a Booking, Tournament, Maintenance window or Venue Closure, depending on {@link #type()}. */
    UUID id();

    static ReservationOwner booking(UUID bookingId) {
        return new Booking(bookingId);
    }

    static ReservationOwner lateConfirmHold(UUID bookingId) {
        return new LateConfirmHold(bookingId);
    }

    static ReservationOwner tournamentApprovalHold(UUID tournamentId) {
        return new TournamentApprovalHold(tournamentId);
    }

    static ReservationOwner tournamentHold(UUID tournamentId) {
        return new TournamentHold(tournamentId);
    }

    static ReservationOwner maintenance(UUID maintenanceId) {
        return new Maintenance(maintenanceId);
    }

    static ReservationOwner closure(UUID closureId) {
        return new Closure(closureId);
    }

    /** {@code BOOKING} - owner {@code booking_id}. */
    record Booking(UUID id) implements ReservationOwner {

        public Booking {
            Objects.requireNonNull(id, "bookingId");
        }

        @Override
        public ReservationType type() {
            return ReservationType.BOOKING;
        }
    }

    /** {@code LATE_CONFIRM_HOLD} - owner {@code booking_id}. */
    record LateConfirmHold(UUID id) implements ReservationOwner {

        public LateConfirmHold {
            Objects.requireNonNull(id, "bookingId");
        }

        @Override
        public ReservationType type() {
            return ReservationType.LATE_CONFIRM_HOLD;
        }
    }

    /** {@code TOURNAMENT_APPROVAL_HOLD} - owner {@code tournament_id}. */
    record TournamentApprovalHold(UUID id) implements ReservationOwner {

        public TournamentApprovalHold {
            Objects.requireNonNull(id, "tournamentId");
        }

        @Override
        public ReservationType type() {
            return ReservationType.TOURNAMENT_APPROVAL_HOLD;
        }
    }

    /** {@code TOURNAMENT_HOLD} - owner {@code tournament_id}. */
    record TournamentHold(UUID id) implements ReservationOwner {

        public TournamentHold {
            Objects.requireNonNull(id, "tournamentId");
        }

        @Override
        public ReservationType type() {
            return ReservationType.TOURNAMENT_HOLD;
        }
    }

    /** {@code MAINTENANCE} - owner {@code maintenance_id}. */
    record Maintenance(UUID id) implements ReservationOwner {

        public Maintenance {
            Objects.requireNonNull(id, "maintenanceId");
        }

        @Override
        public ReservationType type() {
            return ReservationType.MAINTENANCE;
        }
    }

    /** {@code CLOSURE} - owner {@code closure_id}. */
    record Closure(UUID id) implements ReservationOwner {

        public Closure {
            Objects.requireNonNull(id, "closureId");
        }

        @Override
        public ReservationType type() {
            return ReservationType.CLOSURE;
        }
    }
}
