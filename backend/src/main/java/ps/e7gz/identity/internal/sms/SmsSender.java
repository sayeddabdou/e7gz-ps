package ps.e7gz.identity.internal.sms;

/**
 * Sends a phone OTP by SMS (arch 01 section 6: "Behind an {@code SmsSender} port"; Spec v5 section 7.3: the OTP
 * channel is SMS). SMS is used for OTP codes only (FR-NTF-01).
 *
 * <p>Never called inside a business transaction: the OTP row commits first, then the code is sent. The adapter is
 * chosen by {@code e7gz.sms.provider} ({@link SmsProperties}).
 */
public interface SmsSender {

    /**
     * @param phoneE164 the destination in E.164 form, e.g. {@code +201012345678}
     * @param code      the plain 6-digit code; it exists only in memory and is never stored or logged in production
     * @throws SmsDeliveryException when the provider refuses or cannot be reached
     */
    void sendOtp(String phoneE164, String code);
}
