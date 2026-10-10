package ps.e7gz.notification.internal.email;

/**
 * Sends one plain-text email (arch 08 section 4.4: account, security and moderation notices, FR-NTF-03). Chosen by
 * {@code e7gz.email.provider}: {@code smtp} (default; Mailpit on localhost:1025 in local development) or {@code log}.
 */
public interface EmailSender {

    /** The SMTP server's answer, classified (arch 08 section 4.3). */
    sealed interface EmailResult {

        record Sent(String messageId) implements EmailResult {
        }

        /** The address can never receive it (malformed, refused): final, not retried. */
        record Rejected(String errorCode) implements EmailResult {
        }

        /** Server unreachable, timeout, authentication, temporary refusal: retried. */
        record Transient(String errorCode) implements EmailResult {
        }
    }

    EmailResult send(String to, String subject, String body);
}
