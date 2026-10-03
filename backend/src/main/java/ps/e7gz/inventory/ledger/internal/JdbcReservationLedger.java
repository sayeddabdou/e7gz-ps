package ps.e7gz.inventory.ledger.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.ReservationType;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.error.DatabaseErrorCodes;
import ps.e7gz.inventory.ledger.api.NewReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.inventory.ledger.api.SlotTaken;

/**
 * Explicit SQL on the command's own transaction ({@link CommandContext#jdbc()}); no transaction, connection or clock
 * of its own. The database is the authority on occupancy (ADR-003, arch 05 section 3.1): no overlap is checked here
 * before the INSERT. {@code tr_00_prepare_resource_reservation} derives {@code planned_range} on insert and refuses
 * any later change of owner or plan, any re-activation and any rewrite of recorded release facts.
 */
@Component
class JdbcReservationLedger implements ReservationLedger {

    /** {@code planned_range} is omitted: the insert trigger derives it as {@code [planned_start, planned_end)}. */
    static final String INSERT = """
            INSERT INTO inventory.resource_reservation (id, resource_id, reservation_type,
                                                        booking_id, tournament_id, maintenance_id, closure_id,
                                                        planned_start, planned_end, actual_reservation_start)
            VALUES (:id, :resourceId, :reservationType,
                    :bookingId, :tournamentId, :maintenanceId, :closureId,
                    :plannedStart, :plannedEnd, :actualReservationStart)
            """;

    /** One-way release (arch 05 section 3.2): sets the release facts only, and only on a row still active. */
    static final String RELEASE = """
            UPDATE inventory.resource_reservation
               SET active_flag = false,
                   actual_reservation_release_at = :releaseAt,
                   release_reason = :releaseReason
             WHERE id = :id
               AND active_flag = true
               AND actual_reservation_release_at IS NULL
            """;

    /**
     * Check 8 of the availability predicate: active commitment rows only, on the immutable planned interval. A
     * released row ({@code active_flag = false}) keeps its planned interval as history and never blocks (EC-AVAI-004).
     */
    static final String BLOCKING_OVERLAP = """
            SELECT EXISTS (
                SELECT 1
                  FROM inventory.resource_reservation rr
                 WHERE rr.resource_id = :resourceId
                   AND rr.active_flag
                   AND rr.reservation_type IN (:blockingTypes)
                   AND rr.planned_range && tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)'))
            """;

    /**
     * The commitment types check 8 counts. {@code MAINTENANCE} and {@code CLOSURE} rows are technical guards whose
     * owners the predicate reads from Operations (checks 6-7).
     */
    static final List<String> BLOCKING_TYPES = List.of(ReservationType.BOOKING.name(),
            ReservationType.LATE_CONFIRM_HOLD.name(), ReservationType.TOURNAMENT_APPROVAL_HOLD.name(),
            ReservationType.TOURNAMENT_HOLD.name());

    /** I1 exclusion constraint (arch 05 section 3.1). */
    static final String EX_NO_DOUBLE_BOOKING = "ex_no_double_booking";

    /**
     * Raised by {@code tr_10_resource_reservation_cross_class_guard} for a new {@code BOOKING} row: the guard locks the
     * Resource and re-reads committed rows before the exclusion constraint is checked, so in practice a Booking race is
     * lost here, not on {@code ex_no_double_booking}. Treating it as {@link SlotTaken} is an E7-29 implementation
     * decision based on that observed database behaviour and on {@code DatabaseErrorCodes}, which already maps this
     * identifier to {@code SLOT_NO_LONGER_AVAILABLE}.
     */
    static final String GD_RESERVATION_CONFLICT_BOOKING = "gd_reservation_conflict_booking";

    @Override
    public UUID create(CommandContext command, NewReservation reservation) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(reservation, "reservation");
        UUID id = UUID.randomUUID();
        OwnerColumns owner = OwnerColumns.of(reservation.owner());
        try {
            command.jdbc().sql(INSERT)
                    .param("id", id)
                    .param("resourceId", reservation.resourceId())
                    .param("reservationType", reservation.owner().type().name())
                    .param("bookingId", owner.bookingId())
                    .param("tournamentId", owner.tournamentId())
                    .param("maintenanceId", owner.maintenanceId())
                    .param("closureId", owner.closureId())
                    .param("plannedStart", utc(reservation.plannedStart()))
                    .param("plannedEnd", utc(reservation.plannedEnd()))
                    .param("actualReservationStart", utc(command.commandNow()))
                    .update();
        } catch (DataAccessException e) {
            if (isSlotTaken(e, reservation.owner().type())) {
                throw new SlotTaken();
            }
            throw e;
        }
        return id;
    }

    @Override
    public boolean release(CommandContext command, UUID reservationId, Instant releaseAt, String releaseReason) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(releaseAt, "releaseAt");
        Objects.requireNonNull(releaseReason, "releaseReason");
        return command.jdbc().sql(RELEASE)
                .param("id", reservationId)
                .param("releaseAt", utc(releaseAt))
                .param("releaseReason", releaseReason)
                .update() == 1;
    }

    @Override
    public boolean hasBlockingOverlap(CommandContext command, UUID resourceId, Instant start, Instant end) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start: [" + start + ", " + end + ")");
        }
        return command.jdbc().sql(BLOCKING_OVERLAP)
                .param("resourceId", resourceId)
                .param("blockingTypes", BLOCKING_TYPES)
                .param("start", utc(start))
                .param("end", utc(end))
                .query(Boolean.class).single();
    }

    /**
     * {@code 23P01} on {@code ex_no_double_booking} for a {@code BOOKING} row, or {@code P0001} with
     * {@code gd_reservation_conflict_booking}, read from the driver's structured diagnostics (never message text).
     * {@code ex_no_double_booking} on a hold row propagates unchanged and is never SlotTaken (PO decision 3 Oct 2026).
     */
    static boolean isSlotTaken(Throwable failure, ReservationType attempted) {
        Objects.requireNonNull(attempted, "attempted");
        return DatabaseErrorCodes.serverError(failure).map(error -> isSlotTaken(error, attempted)).orElse(false);
    }

    private static boolean isSlotTaken(ServerErrorMessage error, ReservationType attempted) {
        String sqlState = error.getSQLState();
        String constraint = error.getConstraint();
        // PO decision 3 Oct 2026: only a BOOKING row turns ex_no_double_booking into SlotTaken. For a hold,
        // tr_10_resource_reservation_cross_class_guard normally locks the Resource and refuses first with its
        // type-specific P0001, so a raw 23P01 means the guard was bypassed (a defect): propagated unchanged.
        return (attempted == ReservationType.BOOKING
                        && DatabaseErrorCodes.EXCLUSION_VIOLATION.equals(sqlState)
                        && EX_NO_DOUBLE_BOOKING.equals(constraint))
                || (DatabaseErrorCodes.RAISE_EXCEPTION.equals(sqlState)
                        && GD_RESERVATION_CONFLICT_BOOKING.equals(constraint));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** The four owner columns: exactly one is set, as {@code ck_reservation_owner_shape} requires for the type. */
    record OwnerColumns(UUID bookingId, UUID tournamentId, UUID maintenanceId, UUID closureId) {

        static OwnerColumns of(ReservationOwner owner) {
            return switch (owner) {
                case ReservationOwner.Booking o -> new OwnerColumns(o.id(), null, null, null);
                case ReservationOwner.LateConfirmHold o -> new OwnerColumns(o.id(), null, null, null);
                case ReservationOwner.TournamentApprovalHold o -> new OwnerColumns(null, o.id(), null, null);
                case ReservationOwner.TournamentHold o -> new OwnerColumns(null, o.id(), null, null);
                case ReservationOwner.Maintenance o -> new OwnerColumns(null, null, o.id(), null);
                case ReservationOwner.Closure o -> new OwnerColumns(null, null, null, o.id());
            };
        }
    }
}
