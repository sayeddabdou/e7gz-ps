package ps.e7gz.loyalty.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.FeeTreatment;

/**
 * E7-66 - every terminal reason has exactly one fee treatment (FR-PTS-14 to FR-PTS-17, arch 03 section 16). The switch
 * has no default branch, so a new {@link BookingTerminalReason} without a treatment fails compilation; these tests pin
 * each treatment to the FRS lists.
 */
class FeeQueryTest {

    /** FR-PTS-15's closed full-refund list; the ADMIN_* reasons are its "admin cancellation or correction" items. */
    private static final Set<BookingTerminalReason> FR_PTS_15 = EnumSet.of(
            BookingTerminalReason.VENUE_REJECTED,
            BookingTerminalReason.VENUE_DID_NOT_RESPOND_IN_TIME,
            BookingTerminalReason.REJECTED_AFTER_EXPIRY,
            BookingTerminalReason.CUSTOMER_DECLINED_LATE_CONFIRM,
            BookingTerminalReason.CUSTOMER_RECONFIRM_TIMEOUT,
            BookingTerminalReason.VENUE_CANCELLED_CONFIRMED_BOOKING,
            BookingTerminalReason.VENUE_CLOSURE,
            BookingTerminalReason.VENUE_OPENING_HOURS_CHANGED,
            BookingTerminalReason.VENUE_EMERGENCY_CLOSURE,
            BookingTerminalReason.RESOURCE_EMERGENCY_OUTAGE,
            BookingTerminalReason.RESOURCE_OWNER_REVIEW_REQUIRED,
            BookingTerminalReason.VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY,
            BookingTerminalReason.RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY,
            BookingTerminalReason.LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY,
            BookingTerminalReason.VENUE_ACCOUNT_DISABLED,
            BookingTerminalReason.SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR,
            BookingTerminalReason.ADMIN_CANCELLED,
            BookingTerminalReason.ADMIN_CORRECTION,
            BookingTerminalReason.ADMIN_NO_SHOW_REVERSAL);

    @ParameterizedTest
    @EnumSource(BookingTerminalReason.class)
    void everyTerminalReasonHasExactlyOneFeeTreatment(BookingTerminalReason reason) {
        assertThat(FeeQuery.feeTreatmentFor(reason)).isNotNull();
    }

    @Test
    void theFullRefundListIsFrPts15() {
        assertThat(Arrays.stream(BookingTerminalReason.values())
                .filter(reason -> FeeQuery.feeTreatmentFor(reason) == FeeTreatment.FULL_REFUND))
                .containsExactlyInAnyOrderElementsOf(FR_PTS_15);
    }

    @Test
    void customerCancellationAndABanFollowTheCustomerPolicy() {
        // FR-PTS-16
        assertThat(Arrays.stream(BookingTerminalReason.values())
                .filter(reason -> FeeQuery.feeTreatmentFor(reason) == FeeTreatment.CUSTOMER_POLICY))
                .containsExactlyInAnyOrder(BookingTerminalReason.CUSTOMER_CANCELLED,
                        BookingTerminalReason.USER_BANNED);
    }

    @Test
    void aManualBookingCancellationIsNotApplicable() {
        // FR-PTS-16: no customer fee exists
        assertThat(Arrays.stream(BookingTerminalReason.values())
                .filter(reason -> FeeQuery.feeTreatmentFor(reason) == FeeTreatment.NOT_APPLICABLE))
                .containsExactly(BookingTerminalReason.VENUE_CANCELLED_MANUAL_BOOKING);
    }

    @Test
    void decliningOrTimingOutALateConfirmationIsNotAnOrdinaryCustomerCancellation() {
        // FR-PTS-17: Late Confirm exists only through the venue's own expiry
        assertThat(FeeQuery.feeTreatmentFor(BookingTerminalReason.CUSTOMER_DECLINED_LATE_CONFIRM))
                .isEqualTo(FeeTreatment.FULL_REFUND);
        assertThat(FeeQuery.feeTreatmentFor(BookingTerminalReason.CUSTOMER_RECONFIRM_TIMEOUT))
                .isEqualTo(FeeTreatment.FULL_REFUND);
    }

    @Test
    void aNullReasonIsACallerDefect() {
        assertThatThrownBy(() -> FeeQuery.feeTreatmentFor(null)).isInstanceOf(NullPointerException.class);
    }
}
