package ps.e7gz.identity.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.OtpPurpose;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.notification.api.CustomerPushDevices;

/**
 * Phone verification (FR-ACC-02, FR-ACC-04): a valid code consumes the challenge and sets {@code phone_verified} -
 * the account becomes Account Verified.
 *
 * <p>The database refuses any use of an expired, superseded, consumed or exhausted code with one combined error that
 * carries no identifier; the code is decided here from the challenge row read under the User's lock (physical model
 * v1.1.27 change report). A consumed or superseded code is {@code OTP_INVALID} (Product Owner, 30 Sep 2026).
 *
 * <p>A code that did not come with the registration itself - a resend through {@code /otp/send} - must bring a new
 * password (Sprint 0 review, E7-57): it replaces the old one and every refresh token of the account is revoked, so an
 * account someone else created with this phone cannot be kept by them. Its push devices are revoked with them (PO
 * decision 24 Oct 2026, E7-119). The registration's own code was issued in the registration's command, at its
 * {@code joined_at}; any other code was issued later.
 *
 * <p>The outcome is <b>returned</b>, never thrown: a wrong code must commit its attempt, or the 5-attempt limit would
 * never be reached. The caller turns the outcome into the API error after the commit.
 */
record VerifyPhoneCommand(UUID userId, UUID challengeId, String code, String newPasswordHash,
                          CustomerPushDevices pushDevices)
        implements Command<VerifyPhoneCommand.Outcome> {

    enum Outcome { VERIFIED, INVALID, EXPIRED, ATTEMPTS_EXCEEDED, ACCOUNT_DELETED, PASSWORD_REQUIRED }

    private record Account(String phoneE164, boolean anonymised, Instant joinedAt) {
    }

    /** No Venue is in scope, so the category selects no venue rule (ADR-022 names none for account commands). */
    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
    }

    @Override
    public CommandScope scope() {
        return CommandScope.shared(LockSet.builder().users(userId).build());
    }

    @Override
    public Outcome execute(CommandContext ctx) {
        OtpChallenges.Challenge challenge =
                OtpChallenges.find(ctx, challengeId, OtpPurpose.PHONE_VERIFICATION).orElse(null);
        if (challenge == null || !userId.equals(challenge.userId())) {
            return Outcome.INVALID;
        }
        Account account = ctx.jdbc().sql(
                        "SELECT phone_e164, anonymised_at IS NOT NULL AS anonymised, joined_at FROM iam.user_account WHERE id = :id")
                .param("id", userId)
                .query((rs, n) -> new Account(rs.getString("phone_e164"), rs.getBoolean("anonymised"),
                        rs.getObject("joined_at", OffsetDateTime.class).toInstant()))
                .single();
        if (account.anonymised()) {
            return Outcome.ACCOUNT_DELETED;                                                  // FR-ACC-10
        }
        if (challenge.consumedAt() != null || challenge.supersededAt() != null
                || !challenge.destination().equals(account.phoneE164())) {
            return Outcome.INVALID;
        }
        if (!ctx.commandNow().isBefore(challenge.expiresAt())) {
            return Outcome.EXPIRED;
        }
        if (newPasswordHash == null && !challenge.issuedAt().equals(account.joinedAt())) {
            return Outcome.PASSWORD_REQUIRED;                                 // a resend: no attempt is counted
        }
        if (challenge.attempts() >= 5) {
            return Outcome.ATTEMPTS_EXCEEDED;
        }
        if (!OtpCodes.matches(code, challenge.codeHash())) {
            OtpChallenges.countFailedAttempt(ctx, challengeId);
            return Outcome.INVALID;
        }
        OtpChallenges.consume(ctx, challengeId);
        ctx.jdbc().sql("""
                        UPDATE iam.user_account SET phone_verified = true, phone_verified_at = :now
                         WHERE id = :id AND NOT phone_verified
                        """)
                .param("now", OtpChallenges.utc(ctx.commandNow()))
                .param("id", userId)
                .update();
        if (newPasswordHash != null) {
            ctx.jdbc().sql("UPDATE iam.user_account SET password_hash = :hash WHERE id = :id")
                    .param("hash", newPasswordHash).param("id", userId).update();
            RefreshTokens.revokeAll(ctx, AuthSubjectType.CUSTOMER, userId);
            pushDevices.revokeAll(ctx, userId);
        }
        return Outcome.VERIFIED;
    }
}
