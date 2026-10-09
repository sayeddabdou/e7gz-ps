package ps.e7gz.identity.internal;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.identity.internal.sms.SmsSender;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxConsumer;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.messaging.api.PermanentDeliveryFailure;

/**
 * Sends a phone OTP by SMS once its command has committed (E7-41 carry-forward from E7-13; FR-NTF-01, FR-NTF-12): the
 * outbox dispatcher hands it {@code PhoneOtpIssued}, it opens the sealed code and calls the {@link SmsSender}. The
 * destination is read from the challenge itself, so the payload carries no phone number.
 *
 * <p>A challenge that is no longer open - expired after its 5 minutes, superseded by a newer code, or used - is not sent:
 * its code could not be used any more (FR-ACC-04), and the event is done. A provider failure is retried by the outbox
 * (arch 12 section 6.5); a retry that comes after the code expired ends there, so an OTP is rarely dead-lettered. The
 * customer can always ask for a new code after 60 s. Delivery is at least once: a dispatcher that dies right after the
 * provider accepted may send the same code a second time, which is harmless.
 */
@Component
@Profile("worker")
class PhoneOtpSms implements OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(PhoneOtpSms.class);

    private final OtpPayloadCipher cipher;
    private final SmsSender sms;
    private final JdbcClient jdbc;

    PhoneOtpSms(OtpPayloadCipher cipher, SmsSender sms, DataSource appDataSource) {
        this.cipher = cipher;
        this.sms = sms;
        this.jdbc = JdbcClient.create(appDataSource);
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(IntegrationEvents.PHONE_OTP_ISSUED);
    }

    @Override
    public void handle(OutboxEvent event) {
        UUID challengeId = event.uuid("challengeId");
        Challenge challenge = challenge(challengeId).orElseThrow(() -> new PermanentDeliveryFailure(
                "OTP challenge " + challengeId + " does not exist"));
        if (!challenge.open()) {
            log.warn("OTP of challenge {} not sent: it expired, was replaced or was used before it could be sent",
                    challengeId);
            return;
        }
        String code;
        try {
            code = cipher.open(challengeId, event.text("sealedCode"));
        } catch (IllegalArgumentException e) {
            throw new PermanentDeliveryFailure(e.getMessage());           // another key: no retry can open it
        }
        sms.sendOtp(challenge.destination(), code);                      // SmsDeliveryException: retried
    }

    private record Challenge(String destination, boolean open) {
    }

    /** By the database clock (arch 08 section 8), as the verification flow compares it. */
    private Optional<Challenge> challenge(UUID id) {
        return jdbc.sql("""
                        SELECT destination,
                               consumed_at IS NULL AND superseded_at IS NULL AND expires_at > clock_timestamp() AS open
                          FROM iam.otp_challenge
                         WHERE id = :id""")
                .param("id", id)
                .query((rs, row) -> new Challenge(rs.getString("destination"), rs.getBoolean("open")))
                .optional();
    }
}
