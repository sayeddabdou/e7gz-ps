package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Customer response to a Late Confirmation - {@code ck_late_confirmation_customer_response}. */
@Schema(enumAsRef = true)
public enum LateConfirmCustomerResponse {
    ACCEPTED,
    DECLINED,
    TIMED_OUT
}
