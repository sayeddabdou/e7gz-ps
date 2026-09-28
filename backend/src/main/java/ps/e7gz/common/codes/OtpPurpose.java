package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Purpose of an OTP challenge - {@code ck_otp_purpose}. */
@Schema(enumAsRef = true)
public enum OtpPurpose {
    PHONE_VERIFICATION,
    PASSWORD_RESET,
    PHONE_CHANGE
}
