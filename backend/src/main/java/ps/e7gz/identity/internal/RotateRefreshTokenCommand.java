package ps.e7gz.identity.internal;

import java.util.UUID;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;

/**
 * One refresh (arch 10 section 2.3, arch 09 section 2): the presented token is locked and decided on, then either
 * rotated - a new token is issued and the old one gets {@code revoked_at} and {@code rotated_to} - or refused.
 *
 * <p>A token that was already rotated is <b>reuse</b>: everything issued after it is revoked, nothing is issued
 * ("using a rotated token revokes the entire chain"). Two concurrent refreshes of one token serialise on its row lock;
 * the second sees it rotated and revokes the first one's successor, so no two valid successors can exist.
 *
 * <p>Decision order on the locked row: unknown or another audience's token (refused, nothing changes); already rotated
 * (reuse: the chain is revoked); revoked or expired (refused); holder not active (refused, nothing issued); otherwise
 * rotated. Reuse is decided before the account state, so a reused token revokes its chain even when the account has
 * meanwhile been disabled. {@code holderActive} is the owning module's answer for {@code holder}, read just before the
 * command (Identity cannot read another module's table inside its own command).
 *
 * <p>The outcome is <b>returned</b>, never thrown: the revocation of a reused chain must commit.
 */
record RotateRefreshTokenCommand(AuthSubjectType audience, String presentedHash, UUID holder, boolean holderActive,
                                 UUID newTokenId, String newTokenHash)
        implements Command<RotateRefreshTokenCommand.Outcome> {

    sealed interface Outcome {
    }

    record Rotated(UUID subjectId) implements Outcome {
    }

    /** A rotated token was presented again; its chain is now revoked. */
    record Reused(UUID subjectId) implements Outcome {
    }

    /** Unknown, of another audience, revoked or expired; or its holder may not hold a session. Nothing changed. */
    record Refused() implements Outcome {
    }

    /** No Venue is in scope, so the category selects no venue rule (ADR-022 names none for account commands). */
    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
    }

    /** The token row itself is the lock ({@code FOR UPDATE} in the body). */
    @Override
    public CommandScope scope() {
        return CommandScope.shared(LockSet.empty());
    }

    @Override
    public Outcome execute(CommandContext ctx) {
        RefreshTokens.Row token = RefreshTokens.findForUpdate(ctx, presentedHash).orElse(null);
        if (token == null || token.subjectType() != audience) {
            return new Refused();                        // another audience's token is never used or touched here
        }
        if (token.rotatedTo() != null) {
            RefreshTokens.revokeDescendants(ctx, token.id());
            return new Reused(token.subjectId());
        }
        if (token.revokedAt() != null || !ctx.commandNow().isBefore(token.expiresAt())) {
            return new Refused();
        }
        if (!holderActive || !token.subjectId().equals(holder)) {
            return new Refused();                        // e.g. a DISABLED venue account (FR-VEN-08): nothing issued
        }
        if (audience == AuthSubjectType.CUSTOMER && !RefreshTokens.customerMayHoldSession(ctx, token.subjectId())) {
            return new Refused();
        }
        RefreshTokens.insert(ctx, newTokenId, audience, token.subjectId(), newTokenHash);
        ctx.jdbc().sql("UPDATE iam.auth_refresh_token SET revoked_at = :now, rotated_to = :next WHERE id = :id")
                .param("now", OtpChallenges.utc(ctx.commandNow()))
                .param("next", newTokenId)
                .param("id", token.id())
                .update();
        return new Rotated(token.subjectId());
    }
}
