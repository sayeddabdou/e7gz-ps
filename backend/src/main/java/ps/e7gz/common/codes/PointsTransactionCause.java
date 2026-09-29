package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Cause of a loyalty points transaction - {@code ck_points_transaction_cause}. */
@Schema(enumAsRef = true)
public enum PointsTransactionCause {
    ATTENDED_COMPLETION,
    REFERRAL_SIGNUP,
    COMPENSATION,
    ADMIN
}
