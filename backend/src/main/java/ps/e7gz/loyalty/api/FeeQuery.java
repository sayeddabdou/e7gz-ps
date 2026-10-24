package ps.e7gz.loyalty.api;

import java.util.Objects;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.FeeTreatment;

/**
 * The Booking Fee treatment of each terminal reason (arch 03 section 16, Product v5.0 section 34.2): FR-PTS-15's
 * closed full-refund list, FR-PTS-16's customer policy and manual NOT_APPLICABLE, and FR-PTS-17 (declining or timing
 * out a Late Confirmation is not an ordinary customer cancellation). The current fee itself is config's
 * ({@code config.api.BookingFees}, E7-46).
 *
 * <p>{@link #feeTreatmentFor} is a switch over every {@link BookingTerminalReason} with no default branch, so a new
 * reason without a treatment does not compile (FR-PTS-16: "every reachable terminal reason has exactly one fee
 * treatment"). The database decides each refund itself ({@code booking.expected_fee_treatment}, used by
 * {@code loyalty.record_refund}); {@code FeeTreatmentDatabaseParityTest} keeps the two equal for every reason (PO
 * decision 23 Oct 2026, E7-66). An ADMIN_* reason is FULL_REFUND only where the customer is not at fault: the database
 * also requires the Platform Admin's audited service-loss assessment, and pays nothing when the loss is not
 * established (FR-PTS-15, FR-PTS-18).
 */
public final class FeeQuery {

    private FeeQuery() {
    }

    public static FeeTreatment feeTreatmentFor(BookingTerminalReason reason) {
        return switch (Objects.requireNonNull(reason, "reason")) {
            // FR-PTS-16: the customer's own cancellation, and a ban the customer caused
            case CUSTOMER_CANCELLED, USER_BANNED -> FeeTreatment.CUSTOMER_POLICY;
            // FR-PTS-16: a manual booking has no customer and no fee
            case VENUE_CANCELLED_MANUAL_BOOKING -> FeeTreatment.NOT_APPLICABLE;
            // FR-PTS-15 (FR-PTS-17 for the two Late Confirm reasons)
            case VENUE_REJECTED, VENUE_DID_NOT_RESPOND_IN_TIME, REJECTED_AFTER_EXPIRY,
                 CUSTOMER_DECLINED_LATE_CONFIRM, CUSTOMER_RECONFIRM_TIMEOUT,
                 VENUE_CANCELLED_CONFIRMED_BOOKING, VENUE_CLOSURE, VENUE_OPENING_HOURS_CHANGED,
                 VENUE_EMERGENCY_CLOSURE, RESOURCE_EMERGENCY_OUTAGE, RESOURCE_OWNER_REVIEW_REQUIRED,
                 VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY, RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY,
                 LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY, VENUE_ACCOUNT_DISABLED,
                 SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR,
                 ADMIN_CANCELLED, ADMIN_CORRECTION, ADMIN_NO_SHOW_REVERSAL -> FeeTreatment.FULL_REFUND;
        };
    }
}
