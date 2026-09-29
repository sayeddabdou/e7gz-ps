package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Security rate limiter type - {@code ck_rate_limit_limiter_type}. */
@Schema(enumAsRef = true)
public enum RateLimiterType {
    OTP_SEND,
    OTP_ATTEMPT,
    OWNER_PASSWORD,
    LOGIN
}
