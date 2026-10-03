package ps.e7gz.inventory.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.codes.ReservationType;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.inventory.ledger.api.NewReservation;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.inventory.ledger.api.SlotTaken;

/**
 * E7-29 - which database refusals become {@link SlotTaken}, on driver-shaped diagnostics (SQLSTATE + CONSTRAINT);
 * E7-30 - {@code ex_no_double_booking} is SlotTaken only for a {@code BOOKING} row.
 * The {@code ex_no_double_booking} branch is covered here because the cross-class guard refuses an overlapping
 * insert before the exclusion constraint is checked; the guard branch is also proven against the real database in
 * {@code ReservationLedgerIntegrationTest}.
 */
class JdbcReservationLedgerTest {

    private final JdbcReservationLedger ledger = new JdbcReservationLedger();

    private static PSQLException postgres(String sqlState, String constraint) {
        String fields = "SERROR\0VERROR\0C" + sqlState + "\0Mtest refusal\0" + (constraint == null ? "" : "n" + constraint + "\0");
        return new PSQLException(new ServerErrorMessage(fields));
    }

    /** As Spring's JdbcClient reports it: the driver exception is the cause. */
    private static DataAccessException translated(String sqlState, String constraint) {
        return new DataIntegrityViolationException("INSERT failed", postgres(sqlState, constraint));
    }

    @ParameterizedTest
    @CsvSource({
            "23P01, ex_no_double_booking",
            "P0001, gd_reservation_conflict_booking"})
    void exactlyTheBookingSlotConflictsAreSlotTaken(String sqlState, String constraint) {
        assertThat(JdbcReservationLedger.isSlotTaken(translated(sqlState, constraint), ReservationType.BOOKING))
                .isTrue();
    }

    /** E7-30 carry-forward: only a BOOKING row turns {@code ex_no_double_booking} into SlotTaken. */
    @ParameterizedTest
    @EnumSource(value = ReservationType.class, names = "BOOKING", mode = EnumSource.Mode.EXCLUDE)
    void theExclusionViolationOfANonBookingRowIsNotSlotTaken(ReservationType attempted) {
        assertThat(JdbcReservationLedger.isSlotTaken(translated("23P01", "ex_no_double_booking"), attempted))
                .isFalse();
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            // other reservation conflicts keep their own codes
            "P0001, gd_reservation_conflict_late_confirm",
            "P0001, gd_reservation_conflict_tournament",
            "P0001, gd_reservation_conflict_maintenance",
            "P0001, gd_reservation_conflict_closure",
            "P0001, gd_reservation_conflict",
            // another exclusion constraint
            "23P01, ex_customer_no_overlap",
            "23P01, ex_session_no_overlap",
            // the right name under the wrong SQLSTATE
            "P0001, ex_no_double_booking",
            "23505, ex_no_double_booking",
            "23P01, gd_reservation_conflict_booking",
            // FK / unique / no identifier / serialization failure
            "23503, fk_resource_reservation_booking_id__booking_id",
            "23505, resource_reservation_pk",
            "P0001, NULL",
            "23P01, NULL",
            "40001, NULL"})
    void everyOtherDatabaseFailureIsNotSlotTaken(String sqlState, String constraint) {
        assertThat(JdbcReservationLedger.isSlotTaken(translated(sqlState, constraint), ReservationType.BOOKING))
                .isFalse();
    }

    @Test
    void aFailureWithoutPostgresDiagnosticsIsNotSlotTaken() {
        assertThat(JdbcReservationLedger.isSlotTaken(
                new DataIntegrityViolationException("x", new SQLException("x", "23P01")), ReservationType.BOOKING))
                .isFalse();
        assertThat(JdbcReservationLedger.isSlotTaken(new IllegalStateException("ex_no_double_booking"),
                ReservationType.BOOKING)).isFalse();
    }

    // ---- create(): the exclusion branch end to end, on a command whose INSERT fails as the driver reports it ---------

    private static CommandContext commandWhoseInsertFailsWith(DataAccessException failure) {
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
        when(statement.update()).thenThrow(failure);
        JdbcClient jdbc = mock(JdbcClient.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        CommandContext command = mock(CommandContext.class);
        when(command.jdbc()).thenReturn(jdbc);
        when(command.commandNow()).thenReturn(Instant.parse("2030-01-01T07:00:00Z"));
        return command;
    }

    private static NewReservation aBooking() {
        return new NewReservation(UUID.randomUUID(), ReservationOwner.booking(UUID.randomUUID()),
                Instant.parse("2030-01-01T08:00:00Z"), Instant.parse("2030-01-01T09:00:00Z"));
    }

    @Test
    void createTurnsTheExclusionViolationIntoSlotTaken() {
        CommandContext command = commandWhoseInsertFailsWith(translated("23P01", "ex_no_double_booking"));

        assertThatThrownBy(() -> ledger.create(command, aBooking()))
                .isExactlyInstanceOf(SlotTaken.class)
                .extracting(e -> ((SlotTaken) e).code()).isEqualTo(ApiErrorCode.SLOT_NO_LONGER_AVAILABLE);
    }

    /**
     * E7-30 carry-forward (PO decision 3 Oct 2026): the cross-class guard normally locks the Resource and refuses a hold
     * first with its type-specific P0001, so a raw {@code ex_no_double_booking} on a hold means the guard was bypassed
     * (a defect). The ledger propagates that exception unchanged and never turns it into SlotTaken.
     */
    @ParameterizedTest
    @EnumSource(value = ReservationType.class,
            names = {"LATE_CONFIRM_HOLD", "TOURNAMENT_APPROVAL_HOLD", "TOURNAMENT_HOLD"})
    void createRethrowsTheExclusionViolationOfAHoldAsTheSameException(ReservationType type) {
        DataAccessException exclusion = translated("23P01", "ex_no_double_booking");
        CommandContext command = commandWhoseInsertFailsWith(exclusion);
        UUID owner = UUID.randomUUID();
        ReservationOwner hold = switch (type) {
            case LATE_CONFIRM_HOLD -> ReservationOwner.lateConfirmHold(owner);
            case TOURNAMENT_APPROVAL_HOLD -> ReservationOwner.tournamentApprovalHold(owner);
            case TOURNAMENT_HOLD -> ReservationOwner.tournamentHold(owner);
            default -> throw new IllegalArgumentException(type.name());
        };
        NewReservation reservation = new NewReservation(UUID.randomUUID(), hold,
                Instant.parse("2030-01-01T08:00:00Z"), Instant.parse("2030-01-01T09:00:00Z"));

        assertThatThrownBy(() -> ledger.create(command, reservation))
                .isSameAs(exclusion)
                .isNotInstanceOf(SlotTaken.class);
    }

    @Test
    void createRethrowsAnyOtherFailureAsTheSameException() {
        DataAccessException customerOverlap = translated("23P01", "ex_customer_no_overlap");
        CommandContext command = commandWhoseInsertFailsWith(customerOverlap);

        assertThatThrownBy(() -> ledger.create(command, aBooking())).isSameAs(customerOverlap);
    }

    // ---- the SQL itself ---------------------------------------------------------------------------------------------

    @Test
    void releaseSetsOnlyTheReleaseFactsAndOnlyOnAnActiveRow() {
        String release = JdbcReservationLedger.RELEASE.replaceAll("\\s+", " ").trim();
        String set = release.substring(release.indexOf(" SET ") + 5, release.indexOf(" WHERE "));
        String where = release.substring(release.indexOf(" WHERE ") + 7);

        assertThat(set.split(",")).extracting(String::trim).containsExactly(
                "active_flag = false",
                "actual_reservation_release_at = :releaseAt",
                "release_reason = :releaseReason");
        assertThat(where).isEqualTo("id = :id AND active_flag = true AND actual_reservation_release_at IS NULL");
        assertThat(release).doesNotContain("planned_");
    }

    @Test
    void ownerColumnsCarryExactlyTheOwnerOfTheType() {
        UUID id = UUID.randomUUID();

        assertThat(JdbcReservationLedger.OwnerColumns.of(ReservationOwner.booking(id)))
                .isEqualTo(new JdbcReservationLedger.OwnerColumns(id, null, null, null));
        assertThat(JdbcReservationLedger.OwnerColumns.of(ReservationOwner.lateConfirmHold(id)))
                .isEqualTo(new JdbcReservationLedger.OwnerColumns(id, null, null, null));
        assertThat(JdbcReservationLedger.OwnerColumns.of(ReservationOwner.tournamentApprovalHold(id)))
                .isEqualTo(new JdbcReservationLedger.OwnerColumns(null, id, null, null));
        assertThat(JdbcReservationLedger.OwnerColumns.of(ReservationOwner.tournamentHold(id)))
                .isEqualTo(new JdbcReservationLedger.OwnerColumns(null, id, null, null));
        assertThat(JdbcReservationLedger.OwnerColumns.of(ReservationOwner.maintenance(id)))
                .isEqualTo(new JdbcReservationLedger.OwnerColumns(null, null, id, null));
        assertThat(JdbcReservationLedger.OwnerColumns.of(ReservationOwner.closure(id)))
                .isEqualTo(new JdbcReservationLedger.OwnerColumns(null, null, null, id));
    }
}
