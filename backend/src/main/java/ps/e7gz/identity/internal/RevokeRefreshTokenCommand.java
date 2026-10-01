package ps.e7gz.identity.internal;

import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;

/**
 * Logout: revokes the presented refresh token and anything rotated from it, so the session cannot be refreshed again.
 * Rows are kept (revoked, never deleted). A token of another audience, or an unknown one, changes nothing.
 */
record RevokeRefreshTokenCommand(AuthSubjectType audience, String presentedHash) implements Command<Void> {

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
    public Void execute(CommandContext ctx) {
        RefreshTokens.findForUpdate(ctx, presentedHash)
                .filter(token -> token.subjectType() == audience)
                .ifPresent(token -> {
                    ctx.jdbc().sql("UPDATE iam.auth_refresh_token SET revoked_at = :now WHERE id = :id AND revoked_at IS NULL")
                            .param("now", OtpChallenges.utc(ctx.commandNow()))
                            .param("id", token.id())
                            .update();
                    RefreshTokens.revokeDescendants(ctx, token.id());
                });
        return null;
    }
}
