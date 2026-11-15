package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Cause of a loyalty points transaction - {@code ck_points_transaction_cause}. Since PCR-010 Points also pay the
 * Booking Fee: BOOKING_FEE_PAYMENT spends them on a booking's fee, BOOKING_FEE_REFUND gives the same Points back when
 * that fee is refunded.
 */
@Schema(enumAsRef = true)
public enum PointsTransactionCause {
    ATTENDED_COMPLETION,
    REFERRAL_SIGNUP,
    COMPENSATION,
    ADMIN,
    BOOKING_FEE_PAYMENT,
    BOOKING_FEE_REFUND
}
