package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ps.e7gz.booking.internal.ContactVisibility.Facts;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;

/**
 * E7-46 / E7-49 - the counterpart phone rule of arch 10 section 5.1 / FR-PTS-20, row by row, at its exact boundaries:
 * visible strictly before the retention point, hidden from it on - with the expiry cap of PO decision 11 Oct 2026,
 * E7-49 (D1) and the MANUAL exemption.
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
        assertThat(ContactVisibility.visibleAt(online(status, null, null, null), LATER)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"REJECTED", "CANCELLED"})
    void terminalBeforeStartShowsItUntilTerminalAtPlusTwoHours(BookingStatus status) {
        Instant terminal = START.minus(Duration.ofHours(5));
        Facts facts = online(status, terminal, null, null);

        assertVisibleUntil(facts, terminal.plus(TWO_HOURS));
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"COMPLETED", "CANCELLED", "NO_SHOW"})
    void terminalAtOrAfterStartShowsItUntilEndAtPlusTwoHours(BookingStatus status) {
        for (Instant terminal : new Instant[] {START, START.plus(Duration.ofMinutes(20)), END}) {
            assertVisibleUntil(online(status, terminal, null, null), END.plus(TWO_HOURS));
        }
    }

    @Test
    void venueResponseExpiredShowsItUntilExpiredAtPlusTwoHours() {
        Instant expired = START.minus(Duration.ofHours(4));

        assertVisibleUntil(online(BookingStatus.VENUE_RESPONSE_EXPIRED, null, expired, null), expired.plus(TWO_HOURS));
    }

    // ------------------------------------------------------------ PO decision 11 Oct 2026, E7-49 (D1): expiry cap

    @Test
    void rejectedAfterExpiryPastTheExpiryPointStaysHiddenThoughItsOwnPointIsLater() {
        Instant expired = START.minus(Duration.ofHours(6));
        Instant rejected = expired.plus(Duration.ofHours(3));               // after expiry + 2h, before start
        Facts facts = online(BookingStatus.REJECTED, rejected, expired, null);

        assertVisibleUntil(facts, expired.plus(TWO_HOURS));
        assertThat(ContactVisibility.visibleAt(facts, rejected.plus(Duration.ofMinutes(1))))
                .as("never reopened by the later rejection").isFalse();
    }

    @Test
    void cancelledAtStartAfterAnExpiryStaysHiddenThoughEndAtPlusTwoHoursIsLater() {
        Instant expired = START.minus(Duration.ofHours(3));
        Facts facts = online(BookingStatus.CANCELLED, START, expired, null);   // VENUE_DID_NOT_RESPOND_IN_TIME

        assertVisibleUntil(facts, expired.plus(TWO_HOURS));
        assertThat(ContactVisibility.visibleAt(facts, START.plus(Duration.ofMinutes(1)))).isFalse();
    }

    @Test
    void terminalBeforeTheExpiryPointEndsAtTheMinimumOfBothPoints() {
        Instant expired = START.minus(Duration.ofHours(5));
        Instant rejected = expired.plus(Duration.ofMinutes(30));
        Facts facts = online(BookingStatus.REJECTED, rejected, expired, null);

        // min(expiry + 2h, terminalAt + 2h) = expiry + 2h
        assertVisibleUntil(facts, expired.plus(TWO_HOURS));
    }

    @Test
    void aLateConfirmReopensItThroughAwaitingAndAcceptRestoresIt() {
        Instant expired = START.minus(Duration.ofHours(6));
        Instant reopened = expired.plus(Duration.ofHours(3));              // after expiry + 2h

        assertThat(ContactVisibility.visibleAt(
                online(BookingStatus.AWAITING_CUSTOMER_CONFIRMATION, null, expired, reopened), LATER)).isTrue();
        assertThat(ContactVisibility.visibleAt(online(BookingStatus.CONFIRMED, null, expired, reopened), LATER))
                .as("FR-LCF-18: fully restored on Accept").isTrue();
        assertVisibleUntil(online(BookingStatus.COMPLETED, END, expired, reopened), END.plus(TWO_HOURS));
    }

    @Test
    void aDeclinedLateConfirmFollowsTheOrdinaryTerminalBeforeStartRow() {
        Instant expired = START.minus(Duration.ofHours(6));
        Instant reopened = expired.plus(Duration.ofHours(3));
        Instant declined = reopened.plus(Duration.ofMinutes(2));

        assertVisibleUntil(online(BookingStatus.CANCELLED, declined, expired, reopened), declined.plus(TWO_HOURS));
    }

    @ParameterizedTest
    @EnumSource(BookingStatus.class)
    void aManualBookingIsExemptWhateverItsStateOrAge(BookingStatus status) {
        Facts facts = new Facts(BookingSource.MANUAL, status, START, END, START.minus(Duration.ofDays(1)),
                START.minus(Duration.ofDays(2)), null);

        assertThat(ContactVisibility.visibleAt(facts, LATER)).isTrue();
    }

    private static Facts online(BookingStatus status, Instant terminalAt, Instant expiredAt, Instant reopenedAt) {
        return new Facts(BookingSource.ONLINE, status, START, END, terminalAt, expiredAt, reopenedAt);
    }

    private static void assertVisibleUntil(Facts facts, Instant point) {
        assertThat(ContactVisibility.visibleAt(facts, point.minusNanos(1))).as("visible just before %s", point)
                .isTrue();
        assertThat(ContactVisibility.visibleAt(facts, point)).as("hidden at %s", point).isFalse();
        assertThat(ContactVisibility.visibleAt(facts, point.plusSeconds(1))).as("hidden after %s", point).isFalse();
    }
}
