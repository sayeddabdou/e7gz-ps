package ps.e7gz.identity.internal;

import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ps.e7gz.common.codes.OtpPurpose;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.identity.internal.sms.SmsDeliveryException;
import ps.e7gz.identity.internal.sms.SmsSender;

/**
 * Customer registration and phone verification (FRS F-01; FR-ACC-01…06; arch 09 section 2 and section 3.1).
 *
 * <p>Slow or external work stays outside the transaction: the Argon2id hash and the OTP are prepared before the
 * command, and the SMS is sent after it commits (arch 01 section 6: "Never called inside a business transaction").
 * A failed SMS never undoes the committed account or challenge; the customer asks for a new code after 60 s. (The
 * outbox takes over the send and its retries when it exists; Product Owner, 30 Sep 2026.)
 */
@Service
public class CustomerRegistration {

    private static final Logger log = LoggerFactory.getLogger(CustomerRegistration.class);

    private final CommandRunner runner;
    private final PasswordEncoder passwords;
    private final SmsSender sms;
    private final JdbcClient jdbc;

    CustomerRegistration(CommandRunner runner, PasswordEncoder passwords, SmsSender sms, DataSource appDataSource) {
        this.runner = runner;
        this.passwords = passwords;
        this.sms = sms;
        this.jdbc = JdbcClient.create(appDataSource);
    }

    /** What registration returns: the new User and the OTP challenge to verify. */
    public record Registered(UUID userId, UUID otpChallengeId) {
    }

    /** Registration data as the client sent it (FR-ACC-01: all five fields required). */
    public record NewCustomer(String fullName, String username, String phone, String email, String password) {
    }

    public Registered register(NewCustomer customer) {
        if (!PasswordPolicy.isAcceptable(customer.password())) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "password");               // VALIDATION RULES §2.1
        }
        String phone = e164(customer.phone());
        String code = OtpCodes.newCode();
        UUID userId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        runner.run(new RegisterCustomerCommand(
                userId,
                customer.fullName().strip(),
                customer.username().strip(),                                                 // FR-ACC-06: trimmed
                phone,
                customer.email().strip(),
                passwords.encode(customer.password()),                                       // arch 10 §2.2
                ReferralCodes.newCode(),
                challengeId,
                OtpCodes.hash(code)));
        sendAfterCommit(phone, code);
        return new Registered(userId, challengeId);
    }

    /**
     * A new phone-verification code for an account that is not verified yet (the first send or a resend). Returns
     * the new challenge id; the previous code stops working (FR-ACC-04).
     */
    public UUID sendPhoneVerificationOtp(String rawPhone) {
        String phone = e164(rawPhone);
        UUID userId = pendingAccount(phone).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "phone"));
        String code = OtpCodes.newCode();
        UUID challengeId = UUID.randomUUID();
        SendPhoneOtpCommand.Outcome outcome =
                runner.run(new SendPhoneOtpCommand(userId, phone, challengeId, OtpCodes.hash(code)));
        if (outcome == SendPhoneOtpCommand.Outcome.NO_PENDING_VERIFICATION) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "phone");
        }
        sendAfterCommit(phone, code);
        return challengeId;
    }

    /** Checks the code; on success the account is Account Verified (FR-ACC-02). */
    public void verifyPhone(UUID challengeId, String code) {
        UUID userId = challengeOwner(challengeId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.OTP_INVALID, "code"));
        switch (runner.run(new VerifyPhoneCommand(userId, challengeId, code))) {
            case VERIFIED -> {
            }
            case INVALID -> throw new ApiException(ApiErrorCode.OTP_INVALID, "code");
            case EXPIRED -> throw new ApiException(ApiErrorCode.OTP_EXPIRED, "code");
            case ATTEMPTS_EXCEEDED -> throw new ApiException(ApiErrorCode.OTP_ATTEMPTS_EXCEEDED, "code");
            case ACCOUNT_DELETED -> throw new ApiException(ApiErrorCode.ACCOUNT_DELETED);
        }
    }

    private static String e164(String phone) {
        return PhoneNumbers.toE164(phone).orElseThrow(() -> new ApiException(ApiErrorCode.VALIDATION_FAILED, "phone"));
    }

    /** The unverified, non-deleted account with this phone; the command re-checks it under the User's lock. */
    private Optional<UUID> pendingAccount(String phoneE164) {
        return jdbc.sql("""
                        SELECT id FROM iam.user_account
                         WHERE phone_e164 = :phone AND NOT phone_verified AND anonymised_at IS NULL
                        """)
                .param("phone", phoneE164)
                .query(UUID.class)
                .optional();
    }

    /** Whose phone-verification challenge this is; the command re-reads the challenge under that User's lock. */
    private Optional<UUID> challengeOwner(UUID challengeId) {
        return jdbc.sql("SELECT user_id FROM iam.otp_challenge WHERE id = :id AND purpose = :purpose AND user_id IS NOT NULL")
                .param("id", challengeId)
                .param("purpose", OtpPurpose.PHONE_VERIFICATION.name())
                .query(UUID.class)
                .optional();
    }

    private void sendAfterCommit(String phoneE164, String code) {
        try {
            sms.sendOtp(phoneE164, code);
        } catch (SmsDeliveryException e) {
            // Logged by the adapter without the code or the full number; the customer can resend after 60 s.
            log.error("OTP SMS was not delivered; provider code {}", e.providerCode());
        }
    }
}
