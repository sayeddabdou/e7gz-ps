package ps.e7gz.identity.internal;

import java.util.UUID;
import ps.e7gz.common.codes.OtpPurpose;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;

/**
 * Phone verification (FR-ACC-02, FR-ACC-04): a valid code consumes the challenge and sets {@code phone_verified} -
 * the account becomes Account Verified.
 *
 * <p>The database refuses any use of an expired, superseded, consumed or exhausted code with one combined error that
 * carries no identifier; the code is decided here from the challenge row read under the User's lock (physical model
 * v1.1.27 change report). A consumed or superseded code is {@code OTP_INVALID} (Product Owner, 30 Sep 2026).
 *
 * <p>The outcome is <b>returned</b>, never thrown: a wrong code must commit its attempt, or the 5-attempt limit would
 * never be reached. The caller turns the outcome into the API error after the commit.
 */
record VerifyPhoneCommand(UUID userId, UUID challengeId, String code) implements Command<VerifyPhoneCommand.Outcome> {

    enum Outcome { VERIFIED, INVALID, EXPIRED, ATTEMPTS_EXCEEDED, ACCOUNT_DELETED }

    private record Account(String phoneE164, boolean anonymised) {
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
                        "SELECT phone_e164, anonymised_at IS NOT NULL AS anonymised FROM iam.user_account WHERE id = :id")
                .param("id", userId)
                .query((rs, n) -> new Account(rs.getString("phone_e164"), rs.getBoolean("anonymised")))
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
        return Outcome.VERIFIED;
    }
}
