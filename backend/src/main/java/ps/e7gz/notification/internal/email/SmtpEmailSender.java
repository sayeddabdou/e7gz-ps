package ps.e7gz.notification.internal.email;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Plain-text email over SMTP. A malformed address or one the server refuses for good is {@code Rejected}; anything else
 * (connection, timeout, authentication, a temporary refusal) is retried. The address and the body are never logged.
 */
class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mail;
    private final String from;

    SmtpEmailSender(JavaMailSender mail, String from) {
        this.mail = mail;
        this.from = from;
    }

    @Override
    public EmailResult send(String to, String subject, String body) {
        String messageId = "<" + UUID.randomUUID() + "@e7gz>";
        try {
            InternetAddress recipient = new InternetAddress(to, true);
            MimeMessage message = mail.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(body, false);
            message.setHeader("Message-ID", messageId);
            mail.send(message);
            return new EmailResult.Sent(messageId);
        } catch (AddressException e) {
            return new EmailResult.Rejected("EMAIL_ADDRESS_INVALID");
        } catch (MailSendException e) {
            boolean refused = e.getFailedMessages().values().stream().anyMatch(SendFailedException.class::isInstance);
            log.warn("SMTP send failed: {}", refused ? "address refused" : e.getClass().getSimpleName());
            return refused ? new EmailResult.Rejected("EMAIL_ADDRESS_REFUSED")
                    : new EmailResult.Transient("SMTP_SEND_FAILED");
        } catch (MailException | MessagingException e) {
            log.warn("SMTP send failed: {}", e.getClass().getSimpleName());
            return new EmailResult.Transient("SMTP_UNAVAILABLE");
        }
    }
}
