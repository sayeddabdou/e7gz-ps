package ps.e7gz.inventory.availability.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.ScheduleDisplayState;
import ps.e7gz.inventory.availability.api.ScheduleBookings.Facts;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.inventory.ledger.api.ScheduledReservations.Row;

/**
 * E7-112 - a ledger row's display state (FR-VEN-37): BOOKING by the Booking's source, then status; the holds by their
 * row type. An active BOOKING row whose Booking cannot block (arch 05 section 2.3) is a broken invariant, never mapped.
 */
class VenueSchedulesStateTest {

    private static final UUID BOOKING = UUID.randomUUID();
    private static final Instant START = Instant.parse("2031-03-10T10:00:00Z");

    private static Row row(ReservationOwner owner) {
        return new Row(UUID.randomUUID(), UUID.randomUUID(), owner, START, START.plusSeconds(3600));
    }

    private static Map<UUID, Facts> booking(BookingStatus status, BookingSource source) {
        return Map.of(BOOKING, new Facts(BOOKING, status, source));
    }

    @Test
    void anOnlineBookingShowsItsBlockingStatus() {
        Row row = row(ReservationOwner.booking(BOOKING));

        assertThat(VenueSchedules.stateOf(row, booking(BookingStatus.PENDING, BookingSource.ONLINE)))
                .isEqualTo(ScheduleDisplayState.PENDING);
        assertThat(VenueSchedules.stateOf(row, booking(BookingStatus.CONFIRMED, BookingSource.ONLINE)))
                .isEqualTo(ScheduleDisplayState.CONFIRMED);
        assertThat(VenueSchedules.stateOf(row, booking(BookingStatus.ACTIVE, BookingSource.ONLINE)))
                .isEqualTo(ScheduleDisplayState.ACTIVE);
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"CONFIRMED", "ACTIVE"})
    void aManualBookingIsManualWhateverItsStatus(BookingStatus status) {
        assertThat(VenueSchedules.stateOf(row(ReservationOwner.booking(BOOKING)),
                booking(status, BookingSource.MANUAL))).isEqualTo(ScheduleDisplayState.MANUAL);
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"PENDING", "CONFIRMED", "ACTIVE"}, mode = EnumSource.Mode.EXCLUDE)
    void anActiveBookingRowForANonBlockingOnlineStatusIsAnInvariantFailure(BookingStatus status) {
        assertThatThrownBy(() -> VenueSchedules.stateOf(row(ReservationOwner.booking(BOOKING)),
                booking(status, BookingSource.ONLINE)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(status.name());
    }

    @Test
    void anActiveBookingRowWithoutItsBookingIsAnInvariantFailure() {
        assertThatThrownBy(() -> VenueSchedules.stateOf(row(ReservationOwner.booking(BOOKING)), Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(BOOKING.toString());
    }

    @Test
    void theHoldsAreDisplayedByTheirRowTypeWithoutReadingTheBooking() {
        assertThat(VenueSchedules.stateOf(row(ReservationOwner.lateConfirmHold(BOOKING)), Map.of()))
                .isEqualTo(ScheduleDisplayState.AWAITING_CUSTOMER_CONFIRMATION);
        assertThat(VenueSchedules.stateOf(row(ReservationOwner.lateConfirmHold(BOOKING)),
                booking(BookingStatus.CONFIRMED, BookingSource.ONLINE)))
                .as("a late-confirmed Booking's hold is still AWAITING by row type")
                .isEqualTo(ScheduleDisplayState.AWAITING_CUSTOMER_CONFIRMATION);
        assertThat(VenueSchedules.stateOf(row(ReservationOwner.tournamentApprovalHold(UUID.randomUUID())), Map.of()))
                .isEqualTo(ScheduleDisplayState.TOURNAMENT_HOLD);
        assertThat(VenueSchedules.stateOf(row(ReservationOwner.tournamentHold(UUID.randomUUID())), Map.of()))
                .isEqualTo(ScheduleDisplayState.TOURNAMENT_HOLD);
    }

    @Test
    void theDisplayStatesAreExactlyTheNineOfFrVen37() {
        assertThat(EnumSet.allOf(ScheduleDisplayState.class)).extracting(Enum::name).containsExactly("FREE",
                "PENDING", "AWAITING_CUSTOMER_CONFIRMATION", "CONFIRMED", "ACTIVE", "MANUAL", "TOURNAMENT_HOLD",
                "MAINTENANCE", "CLOSURE");
    }
}
