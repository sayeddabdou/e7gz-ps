package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Fee treatment of a terminal booking - {@code ck_booking_fee_treatment_values}, {@code
 * ck_booking_fee_refund_treatment}.
 */
@Schema(enumAsRef = true)
public enum FeeTreatment {
    FULL_REFUND,
    CUSTOMER_POLICY,
    NOT_APPLICABLE
}
