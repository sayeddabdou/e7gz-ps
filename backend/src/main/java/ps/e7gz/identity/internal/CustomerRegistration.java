package ps.e7gz.identity.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ps.e7gz.common.codes.OtpPurpose;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.notification.api.CustomerPushDevices;

/**
 * Customer registration and phone verification (FRS F-01; FR-ACC-01…06; arch 09 section 2 and section 3.1).
 *
 * <p>Slow or external work stays outside the transaction: the Argon2id hash and the OTP are prepared before the
 * command. The command writes the challenge and an outbox event carrying the sealed code; the worker's dispatcher sends
 * the SMS after commit, with retries (E7-41, ADR-004; arch 01 section 6: "Never called inside a business transaction").
 * A failed SMS never undoes the committed account or challenge; the customer can also ask for a new code after 60 s.
 */
@Service
public class CustomerRegistration {

    private final CommandRunner runner;
    private final PasswordEncoder passwords;
    private final OtpPayloadCipher otpCipher;
    private final Outbox outbox;
    private final CustomerPushDevices pushDevices;
    private final JdbcClient jdbc;

    CustomerRegistration(CommandRunner runner, PasswordEncoder passwords, OtpPayloadCipher otpCipher, Outbox outbox,
                         CustomerPushDevices pushDevices, DataSource appDataSource) {
        this.runner = runner;
        this.passwords = passwords;
        this.otpCipher = otpCipher;
        this.outbox = outbox;
        this.pushDevices = pushDevices;
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
        String username = customer.username().strip();                                   // FR-ACC-06: trimmed
        String code = OtpCodes.newCode();
        UUID userId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        // S-3 (E7-57): an unverified holder of the phone or username is locked and released by the command
        List<UUID> holders = UnverifiedAccounts.holders(jdbc, phone, username).stream()
                .map(UnverifiedAccounts.Holder::userId).toList();
        runner.run(new RegisterCustomerCommand(
                userId,
                customer.fullName().strip(),
                username,
                phone,
                customer.email().strip(),
                passwords.encode(customer.password()),                                       // arch 10 §2.2
                ReferralCodes.newCode(),
                challengeId,
                OtpCodes.hash(code),
                otpCipher.seal(challengeId, code),
                outbox,
                holders,
                pushDevices));
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
                runner.run(new SendPhoneOtpCommand(userId, phone, challengeId, OtpCodes.hash(code),
                        otpCipher.seal(challengeId, code), outbox));
        if (outcome == SendPhoneOtpCommand.Outcome.NO_PENDING_VERIFICATION) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "phone");
        }
        return challengeId;
    }

    /**
     * Checks the code; on success the account is Account Verified (FR-ACC-02). Returns the verified User. A code from
     * {@code /otp/send} needs {@code password}, which replaces the old one and ends every session (Sprint 0 review,
     * E7-57); for the registration's own code it is optional and, when given, replaces it the same way.
     */
    public UUID verifyPhone(UUID challengeId, String code, String password) {
        if (password != null && !PasswordPolicy.isAcceptable(password)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "password");               // VALIDATION RULES §2.1
        }
        UUID userId = challengeOwner(challengeId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.OTP_INVALID, "code"));
        String hash = password == null ? null : passwords.encode(password);           // the Argon2id gate (E7-24)
        return switch (runner.run(new VerifyPhoneCommand(userId, challengeId, code, hash, pushDevices))) {
            case VERIFIED -> userId;
            case INVALID -> throw new ApiException(ApiErrorCode.OTP_INVALID, "code");
            case EXPIRED -> throw new ApiException(ApiErrorCode.OTP_EXPIRED, "code");
            case ATTEMPTS_EXCEEDED -> throw new ApiException(ApiErrorCode.OTP_ATTEMPTS_EXCEEDED, "code");
            case ACCOUNT_DELETED -> throw new ApiException(ApiErrorCode.ACCOUNT_DELETED);
            case PASSWORD_REQUIRED -> throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "password");
        };
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
}
