package ps.e7gz.identity.internal;

import java.util.UUID;
import ps.e7gz.common.codes.OtpPurpose;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;

/**
 * A new phone-verification OTP for an unverified account (FR-ACC-04). The database refuses a resend inside 60 s and a
 * 6th send in the hour, and supersedes the previous code. Under the User's lock the account is re-read: it may have
 * been verified or deleted since the caller looked it up.
 */
record SendPhoneOtpCommand(UUID userId, String phoneE164, UUID challengeId, String codeHash)
        implements Command<SendPhoneOtpCommand.Outcome> {

    enum Outcome { SENT, NO_PENDING_VERIFICATION }

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
        boolean pending = ctx.jdbc().sql("""
                        SELECT count(*) FROM iam.user_account
                         WHERE id = :id AND phone_e164 = :phone AND NOT phone_verified AND anonymised_at IS NULL
                        """)
                .param("id", userId)
                .param("phone", phoneE164)
                .query(Integer.class).single() == 1;
        if (!pending) {
            return Outcome.NO_PENDING_VERIFICATION;
        }
        OtpChallenges.insert(ctx, challengeId, userId, OtpPurpose.PHONE_VERIFICATION, phoneE164, codeHash);
        return Outcome.SENT;
    }
}
