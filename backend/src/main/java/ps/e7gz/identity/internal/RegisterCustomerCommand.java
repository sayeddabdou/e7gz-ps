package ps.e7gz.identity.internal;

import java.util.List;
import java.util.UUID;
import ps.e7gz.common.codes.OtpPurpose;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.messaging.api.Outbox;

/**
 * Registration (FR-ACC-01, FR-ACC-06, arch 09 section 2): the account, unverified, and its first phone OTP, in one
 * transaction, with the outbox event that sends the code once it commits (E7-41). Username and Phone uniqueness are
 * the database's ({@code ux_user_username_normalized}, {@code uq_user_account_phone_e164}); their violations reach
 * the client as {@code USERNAME_TAKEN} / {@code PHONE_TAKEN}. Every value is prepared before the command, so a retry reruns only SQL.
 *
 * <p>An account that never verified its phone does not keep them (Sprint 0 review S-3, E7-57): under its lock it is
 * released first ({@link UnverifiedAccounts}), and the registration goes ahead. A verified holder keeps them.
 */
record RegisterCustomerCommand(UUID userId, String fullName, String username, String phoneE164, String email,
                               String passwordHash, String referralCode, UUID challengeId, String codeHash,
                               String sealedCode, Outbox outbox, List<UUID> holders)
        implements Command<Void> {

    /** No Venue is in scope, so the category selects no venue rule (ADR-022 names none for account commands). */
    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
    }

    /** The new User does not exist yet; the accounts that hold its phone or username are locked to be released. */
    @Override
    public CommandScope scope() {
        return CommandScope.shared(LockSet.builder().users(holders).build());
    }

    @Override
    public Void execute(CommandContext ctx) {
        // Only the holders this command locked; one that appeared since meets the unique index below (*_TAKEN)
        UnverifiedAccounts.releaseUnverified(ctx, UnverifiedAccounts.holders(ctx, phoneE164, username).stream()
                .filter(holder -> holders.contains(holder.userId())).toList());
        ctx.jdbc().sql("""
                        INSERT INTO iam.user_account (id, full_name, username, phone_e164, email, password_hash,
                                                      phone_verified, phone_verified_at, email_verified,
                                                      referral_code, joined_at)
                        VALUES (:id, :fullName, :username, :phone, :email, :passwordHash,
                                false, NULL, false, :referralCode, :joinedAt)
                        """)
                .param("id", userId)
                .param("fullName", fullName)
                .param("username", username)
                .param("phone", phoneE164)
                .param("email", email)
                .param("passwordHash", passwordHash)
                .param("referralCode", referralCode)
                .param("joinedAt", OtpChallenges.utc(ctx.commandNow()))
                .update();
        OtpChallenges.issue(ctx, outbox, challengeId, userId, OtpPurpose.PHONE_VERIFICATION, phoneE164, codeHash,
                sealedCode);
        return null;
    }
}
