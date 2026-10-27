package ps.e7gz.identity.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.NotificationRecipientType;
import ps.e7gz.common.codes.OtpPurpose;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.loyalty.api.ReferralSettlement;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.notification.api.CustomerPushDevices;
import ps.e7gz.notification.api.NotificationRequest;
import ps.e7gz.notification.api.Notifications;

/**
 * The customer's own profile and the forgotten-password reset (Spec v5 §7.5, §7.6; FR-ACC-06…09; E7-57, contract
 * E7-54).
 *
 * <ul>
 *   <li>Full name: freely editable.</li>
 *   <li>Username: unique on the trimmed, case-insensitive form ({@code ux_user_username_normalized}); the 30-day
 *       cooldown is the database's ({@code gd_username_cooldown} → {@code USERNAME_COOLDOWN_ACTIVE}). The profile says
 *       when the next change is allowed ({@code usernameChangeAvailableAt}).</li>
 *   <li>Phone: two steps - a {@code PHONE_CHANGE} code to the new phone, then the code. The phone changes only once
 *       verified (FR-ACC-07); a phone of another verified account is {@code PHONE_TAKEN}, an unverified holder is
 *       released (Sprint 0 review S-3).</li>
 *   <li>Password reset: a {@code PASSWORD_RESET} code to the account's verified phone, then the code and a new password;
 *       no old password (FR-ACC-09). Every refresh token is revoked (PO decision 11 Oct 2026, E7-54), and every push
 *       device with them (PO decision 24 Oct 2026, E7-119).</li>
 * </ul>
 * OTP limits are the database's (FR-ACC-04). A code is decided from the challenge read under the User's lock, and a
 * wrong one commits its attempt: the outcome is returned from the command and thrown after the commit, as in
 * {@link VerifyPhoneCommand}. Slow work - the Argon2id hash through the gate (E7-24), the new code - is prepared before
 * the command. {@code PASSWORD_RESET} and {@code PHONE_CHANGED} notify the customer in the same command.
 */
@Service
public class CustomerProfiles {

    /** What the customer sees and edits (the contract's {@code CustomerProfile}). */
    public record Profile(UUID userId, String fullName, String username, String phone, boolean phoneVerified,
                          String email, boolean emailVerified, Instant usernameChangeAvailableAt, Instant joinedAt) {
    }

    enum CodeOutcome { DONE, INVALID, EXPIRED, ATTEMPTS_EXCEEDED, ACCOUNT_DELETED, PHONE_TAKEN }

    private final CommandRunner runner;
    private final PasswordEncoder passwords;
    private final OtpPayloadCipher otpCipher;
    private final Outbox outbox;
    private final Notifications notifications;
    private final CustomerPushDevices pushDevices;
    private final ReferralSettlement referrals;
    private final JdbcClient jdbc;

    CustomerProfiles(CommandRunner runner, PasswordEncoder passwords, OtpPayloadCipher otpCipher, Outbox outbox,
                     Notifications notifications, CustomerPushDevices pushDevices, ReferralSettlement referrals,
                     DataSource appDataSource) {
        this.runner = runner;
        this.passwords = passwords;
        this.otpCipher = otpCipher;
        this.outbox = outbox;
        this.notifications = notifications;
        this.pushDevices = pushDevices;
        this.referrals = referrals;
        this.jdbc = JdbcClient.create(appDataSource);
    }

    // ------------------------------------------------------------------------------------------------- profile

    /** Also for a banned customer (FR-ACC-17). An anonymised account never holds a token (FR-ACC-10). */
    public Profile get(UUID userId) {
        return read(jdbc, userId);
    }

    /** FR-ACC-07: the full name, freely. */
    public Profile changeFullName(UUID userId, String fullName) {
        String name = Objects.requireNonNull(fullName, "fullName").strip();
        if (name.isEmpty()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "fullName");
        }
        return runner.run(new UserCommand<>(userId, ctx -> {
            requireActive(ctx, userId);
            ctx.jdbc().sql("UPDATE iam.user_account SET full_name = :name WHERE id = :id")
                    .param("name", name).param("id", userId).update();
            return read(ctx.jdbc(), userId);
        }));
    }

    /**
     * FR-ACC-06, FR-ACC-07: the username, trimmed. Exactly the current username changes nothing and starts no
     * cooldown; another spelling of it (case) is a change. {@code USERNAME_TAKEN} and {@code USERNAME_COOLDOWN_ACTIVE} come from the database.
     */
    public Profile changeUsername(UUID userId, String username) {
        String wanted = Objects.requireNonNull(username, "username").strip();
        if (wanted.isEmpty()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "username");
        }
        return runner.run(new UserCommand<>(userId, ctx -> {
            requireActive(ctx, userId);
            ctx.jdbc().sql("""
                            UPDATE iam.user_account SET username = :username
                             WHERE id = :id AND username IS DISTINCT FROM :username""")
                    .param("username", wanted).param("id", userId).update();
            return read(ctx.jdbc(), userId);
        }));
    }

    /**
     * FR-ACC-07, step 1: a {@code PHONE_CHANGE} code to the new phone. The account's own phone is
     * {@code VALIDATION_FAILED} (an unverified one is verified through {@code /otp/send}); a phone of another verified
     * account is {@code PHONE_TAKEN} now, before any SMS.
     */
    public UUID startPhoneChange(UUID userId, String rawPhone) {
        String phone = e164(rawPhone);
        String code = OtpCodes.newCode();
        UUID challengeId = UUID.randomUUID();
        String codeHash = OtpCodes.hash(code);
        String sealed = otpCipher.seal(challengeId, code);
        runner.run(new UserCommand<Void>(userId, ctx -> {
            String current = requireActive(ctx, userId);
            if (current.equals(phone)) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "phone");
            }
            if (UnverifiedAccounts.holders(ctx, phone, null).stream().anyMatch(UnverifiedAccounts.Holder::phoneVerified)) {
                throw new ApiException(ApiErrorCode.PHONE_TAKEN, "phone");
            }
            OtpChallenges.issue(ctx, outbox, challengeId, userId, OtpPurpose.PHONE_CHANGE, phone, codeHash, sealed);
            return null;
        }));
        return challengeId;
    }

    /**
     * FR-ACC-07, step 2: the code; the new phone becomes the account's phone, verified at this instant. A challenge that
     * is not this customer's phone change is {@code NOT_FOUND}. A phone a verified account took meanwhile is
     * {@code PHONE_TAKEN} and the code stays usable; an unverified holder is released (S-3). An account that was not
     * verified yet becomes Account Verified here too, so its PENDING referral is settled in the same transaction, its
     * referrer locked with it (FR-PTS-06, E7-67).
     */
    public Profile verifyPhoneChange(UUID userId, UUID challengeId, String code) {
        String destination = jdbc.sql("""
                        SELECT destination FROM iam.otp_challenge
                         WHERE id = :id AND purpose = 'PHONE_CHANGE' AND user_id = :user""")
                .param("id", challengeId).param("user", userId).query(String.class).optional()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        List<UUID> holders = UnverifiedAccounts.holders(jdbc, destination, null).stream()
                .map(UnverifiedAccounts.Holder::userId).filter(id -> !id.equals(userId)).toList();
        UUID referrer = referrals.pendingReferrer(userId).orElse(null);
        List<UUID> locked = referrer == null ? holders
                : Stream.concat(holders.stream(), Stream.of(referrer)).toList();
        CodeOutcome outcome = runner.run(new UserCommand<>(userId, locked, ctx -> {
            Optional<OtpChallenges.Challenge> challenge = OtpChallenges.find(ctx, challengeId, OtpPurpose.PHONE_CHANGE);
            CodeOutcome checked = checkCode(ctx, userId, challengeId, challenge, code, null);
            if (checked != CodeOutcome.DONE) {
                return checked;
            }
            List<UnverifiedAccounts.Holder> held = UnverifiedAccounts.holders(ctx, destination, null).stream()
                    .filter(holder -> !holder.userId().equals(userId)).toList();
            if (held.stream().anyMatch(holder -> holder.phoneVerified() || !holders.contains(holder.userId()))) {
                return CodeOutcome.PHONE_TAKEN;                                  // nothing written; the code stays
            }
            UnverifiedAccounts.releaseUnverified(ctx, held, pushDevices);
            OtpChallenges.consume(ctx, challengeId);
            ctx.jdbc().sql("""
                            UPDATE iam.user_account
                               SET phone_e164 = :phone, phone_verified = true, phone_verified_at = :now
                             WHERE id = :id""")
                    .param("phone", destination).param("now", OtpChallenges.utc(ctx.commandNow()))
                    .param("id", userId).update();
            if (referrer != null) {
                referrals.settle(ctx, userId, referrer);                                  // FR-PTS-06, E7-67
            }
            notifications.notify(ctx, NotificationRequest.of("PHONE_CHANGED", NotificationRecipientType.CUSTOMER,
                    userId, Map.of()));
            return CodeOutcome.DONE;
        }));
        throwFor(outcome);
        return read(jdbc, userId);
    }

    // ------------------------------------------------------------------------------------------------- reset

    /**
     * FR-ACC-09: a {@code PASSWORD_RESET} code to the account's verified phone. A phone with no account, or whose
     * account never verified it, is {@code NOT_FOUND}, like {@code /otp/send} (PO decision 11 Oct 2026, E7-54).
     */
    public UUID requestPasswordReset(String rawPhone) {
        String phone = e164(rawPhone);
        UUID userId = verifiedAccount(phone).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "phone"));
        String code = OtpCodes.newCode();
        UUID challengeId = UUID.randomUUID();
        String codeHash = OtpCodes.hash(code);
        String sealed = otpCipher.seal(challengeId, code);
        boolean issued = runner.run(new UserCommand<>(userId, ctx -> {
            boolean stillVerified = Boolean.TRUE.equals(ctx.jdbc().sql("""
                            SELECT phone_verified AND anonymised_at IS NULL AND phone_e164 = :phone
                              FROM iam.user_account WHERE id = :id""")
                    .param("phone", phone).param("id", userId).query(Boolean.class).single());
            if (stillVerified) {
                OtpChallenges.issue(ctx, outbox, challengeId, userId, OtpPurpose.PASSWORD_RESET, phone, codeHash,
                        sealed);
            }
            return stillVerified;
        }));
        if (!issued) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "phone");
        }
        return challengeId;
    }

    /**
     * FR-ACC-09: the code and the new password (VALIDATION RULES §2.1), no old password. Every refresh token of the
     * account is revoked, so the customer logs in again (PO decision 11 Oct 2026, E7-54). An unknown code is
     * {@code OTP_INVALID}.
     */
    public void completePasswordReset(UUID challengeId, String code, String password) {
        if (!PasswordPolicy.isAcceptable(password)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "password");
        }
        UUID userId = jdbc.sql("""
                        SELECT user_id FROM iam.otp_challenge
                         WHERE id = :id AND purpose = 'PASSWORD_RESET' AND user_id IS NOT NULL""")
                .param("id", challengeId).query(UUID.class).optional()
                .orElseThrow(() -> new ApiException(ApiErrorCode.OTP_INVALID, "code"));
        String hash = passwords.encode(password);                                  // the Argon2id gate (E7-24)
        CodeOutcome outcome = runner.run(new UserCommand<>(userId, ctx -> {
            Optional<OtpChallenges.Challenge> challenge =
                    OtpChallenges.find(ctx, challengeId, OtpPurpose.PASSWORD_RESET);
            CodeOutcome checked = checkCode(ctx, userId, challengeId, challenge, code, "verified");
            if (checked != CodeOutcome.DONE) {
                return checked;
            }
            OtpChallenges.consume(ctx, challengeId);
            ctx.jdbc().sql("UPDATE iam.user_account SET password_hash = :hash WHERE id = :id")
                    .param("hash", hash).param("id", userId).update();
            RefreshTokens.revokeAll(ctx, AuthSubjectType.CUSTOMER, userId);
            pushDevices.revokeAll(ctx, userId);
            notifications.notify(ctx, NotificationRequest.of("PASSWORD_RESET", NotificationRecipientType.CUSTOMER,
                    userId, Map.of()));
            return CodeOutcome.DONE;
        }));
        throwFor(outcome);
    }

    // ------------------------------------------------------------------------------------------------- shared

    /**
     * The code against its challenge, under the User's lock, as {@link VerifyPhoneCommand} decides it. For a reset
     * ({@code phoneMustBe = "verified"}) the code must have gone to the account's current, verified phone.
     */
    private static CodeOutcome checkCode(CommandContext ctx, UUID userId, UUID challengeId,
                                         Optional<OtpChallenges.Challenge> found, String code, String phoneMustBe) {
        OtpChallenges.Challenge challenge = found.orElse(null);
        if (challenge == null || !userId.equals(challenge.userId())) {
            return CodeOutcome.INVALID;
        }
        record Account(String phone, boolean verified, boolean anonymised) {
        }
        Account account = ctx.jdbc().sql("""
                        SELECT phone_e164, phone_verified, anonymised_at IS NOT NULL AS anonymised
                          FROM iam.user_account WHERE id = :id""")
                .param("id", userId)
                .query((rs, n) -> new Account(rs.getString("phone_e164"), rs.getBoolean("phone_verified"),
                        rs.getBoolean("anonymised")))
                .single();
        if (account.anonymised()) {
            return CodeOutcome.ACCOUNT_DELETED;                                      // FR-ACC-10
        }
        if (challenge.consumedAt() != null || challenge.supersededAt() != null) {
            return CodeOutcome.INVALID;
        }
        if (phoneMustBe != null && (!account.verified() || !challenge.destination().equals(account.phone()))) {
            return CodeOutcome.INVALID;                                              // the phone changed since
        }
        if (!ctx.commandNow().isBefore(challenge.expiresAt())) {
            return CodeOutcome.EXPIRED;
        }
        if (challenge.attempts() >= 5) {
            return CodeOutcome.ATTEMPTS_EXCEEDED;
        }
        if (!OtpCodes.matches(code, challenge.codeHash())) {
            OtpChallenges.countFailedAttempt(ctx, challengeId);
            return CodeOutcome.INVALID;
        }
        return CodeOutcome.DONE;
    }

    private static void throwFor(CodeOutcome outcome) {
        switch (outcome) {
            case DONE -> {
            }
            case INVALID -> throw new ApiException(ApiErrorCode.OTP_INVALID, "code");
            case EXPIRED -> throw new ApiException(ApiErrorCode.OTP_EXPIRED, "code");
            case ATTEMPTS_EXCEEDED -> throw new ApiException(ApiErrorCode.OTP_ATTEMPTS_EXCEEDED, "code");
            case ACCOUNT_DELETED -> throw new ApiException(ApiErrorCode.ACCOUNT_DELETED);
            case PHONE_TAKEN -> throw new ApiException(ApiErrorCode.PHONE_TAKEN, "phone");
        }
    }

    /** The account's current phone; an anonymised account is {@code ACCOUNT_DELETED} (FR-ACC-10). */
    private static String requireActive(CommandContext ctx, UUID userId) {
        record Row(String phone, boolean anonymised) {
        }
        Row row = ctx.jdbc().sql("""
                        SELECT phone_e164, anonymised_at IS NOT NULL AS anonymised FROM iam.user_account
                         WHERE id = :id""")
                .param("id", userId)
                .query((rs, n) -> new Row(rs.getString("phone_e164"), rs.getBoolean("anonymised")))
                .optional().orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        if (row.anonymised()) {
            throw new ApiException(ApiErrorCode.ACCOUNT_DELETED);
        }
        return row.phone();
    }

    private static Profile read(JdbcClient jdbc, UUID userId) {
        Instant available = UnverifiedAccounts.usernameChangeAvailableAt(jdbc, userId).orElse(null);
        return jdbc.sql("""
                        SELECT id, full_name, username, phone_e164, phone_verified, email, email_verified, joined_at
                          FROM iam.user_account WHERE id = :id AND anonymised_at IS NULL""")
                .param("id", userId)
                .query((rs, n) -> new Profile(rs.getObject("id", UUID.class), rs.getString("full_name"),
                        rs.getString("username"), rs.getString("phone_e164"), rs.getBoolean("phone_verified"),
                        rs.getString("email"), rs.getBoolean("email_verified"), available,
                        rs.getObject("joined_at", OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(() -> new ApiException(ApiErrorCode.ACCOUNT_DELETED));
    }

    private Optional<UUID> verifiedAccount(String phoneE164) {
        return jdbc.sql("""
                        SELECT id FROM iam.user_account
                         WHERE phone_e164 = :phone AND phone_verified AND anonymised_at IS NULL""")
                .param("phone", phoneE164).query(UUID.class).optional();
    }

    private static String e164(String phone) {
        return PhoneNumbers.toE164(phone).orElseThrow(() -> new ApiException(ApiErrorCode.VALIDATION_FAILED, "phone"));
    }

    /** A command on one customer's account, locking that User (and the accounts it may release). */
    private record UserCommand<R>(UUID userId, List<UUID> alsoLocked, Function<CommandContext, R> body)
            implements Command<R> {

        UserCommand(UUID userId, Function<CommandContext, R> body) {
            this(userId, List.of(), body);
        }

        /** No Venue is in scope, so the category selects no venue rule (ADR-022 names none for account commands). */
        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().users(userId).users(alsoLocked).build());
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }
}
