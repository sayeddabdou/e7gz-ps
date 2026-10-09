package ps.e7gz.messaging.api;

/**
 * An outbox consumer's failure that no retry can fix (arch 08 section 4.3: malformed, invalid recipient, ...): the
 * dispatcher moves the event straight to {@code DEAD_LETTER} instead of retrying it. The message is kept as
 * {@code last_error}, so it must never carry a secret, an OTP or a full phone number (arch 10 section 6).
 */
public class PermanentDeliveryFailure extends RuntimeException {

    public PermanentDeliveryFailure(String message) {
        super(message);
    }

    public PermanentDeliveryFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
