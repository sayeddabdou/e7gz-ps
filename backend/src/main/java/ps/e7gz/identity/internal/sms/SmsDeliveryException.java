package ps.e7gz.identity.internal.sms;

/**
 * The SMS provider refused the message or could not be reached. Carries the provider's own result code when there is
 * one; the message never contains the OTP, the credentials or the full phone number (arch 10 section 2.2, NFR-21).
 */
public class SmsDeliveryException extends RuntimeException {

    private final String providerCode;

    public SmsDeliveryException(String message, String providerCode, Throwable cause) {
        super(message, cause);
        this.providerCode = providerCode;
    }

    /** The provider's result code, or {@code null} when no response was received. */
    public String providerCode() {
        return providerCode;
    }
}
