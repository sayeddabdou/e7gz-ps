package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ps.e7gz.booking.internal.ContactVisibility.Facts;
import ps.e7gz.common.codes.BookingStatus;

/**
 * E7-46 - the counterpart phone rule of arch 10 section 5.1 / FR-PTS-20, row by row, at its exact boundaries: visible
 * strictly before the retention point, hidden from it on.
 */
class ContactVisibilityTest {

    static final Instant START = Instant.parse("2030-06-01T18:00:00Z");
    static final Instant END = START.plus(Duration.ofHours(2));
    static final Duration TWO_HOURS = Duration.ofHours(2);
    static final Instant LATER = END.plus(Duration.ofDays(30));

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class,
            names = {"PENDING", "AWAITING_CUSTOMER_CONFIRMATION", "CONFIRMED", "ACTIVE"})
    void anOpenBookingShowsThePhoneWhateverTheTime(BookingStatus status) {
        assertThat(ContactVisibility.visibleAt(new Facts(status, START, END, null, null), LATER)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"REJECTED", "CANCELLED"})
    void terminalBeforeStartShowsItUntilTerminalAtPlusTwoHours(BookingStatus status) {
        Instant terminal = START.minus(Duration.ofHours(5));
        Facts facts = new Facts(status, START, END, terminal, null);

        assertThat(ContactVisibility.visibleAt(facts, terminal.plus(TWO_HOURS).minusSeconds(1))).isTrue();
        assertThat(ContactVisibility.visibleAt(facts, terminal.plus(TWO_HOURS))).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"COMPLETED", "CANCELLED", "NO_SHOW"})
    void terminalAtOrAfterStartShowsItUntilEndAtPlusTwoHours(BookingStatus status) {
        for (Instant terminal : new Instant[] {START, START.plus(Duration.ofMinutes(20)), END}) {
            Facts facts = new Facts(status, START, END, terminal, null);

            assertThat(ContactVisibility.visibleAt(facts, END.plus(TWO_HOURS).minusSeconds(1))).isTrue();
            assertThat(ContactVisibility.visibleAt(facts, END.plus(TWO_HOURS))).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = "VENUE_RESPONSE_EXPIRED")
    void venueResponseExpiredShowsItUntilExpiredAtPlusTwoHours(BookingStatus status) {
        Instant expired = START.minus(Duration.ofHours(4));
        Facts facts = new Facts(status, START, END, null, expired);

        assertThat(ContactVisibility.visibleAt(facts, expired.plus(TWO_HOURS).minusSeconds(1))).isTrue();
        assertThat(ContactVisibility.visibleAt(facts, expired.plus(TWO_HOURS))).isFalse();
    }
}
