package ps.e7gz.identity.internal;

import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;

/**
 * Starts a session: the first refresh token of a new chain (arch 10 section 2.3). Returns false when a customer was
 * anonymised meanwhile (FR-ACC-10). Venue and admin state was checked by the caller through the owning module and is
 * re-checked on every request and refresh. A staff password login also writes its success audit row here, so the
 * session and its audit commit together (E7-56).
 */
record IssueSessionCommand(AuthSubjectType subjectType, UUID subjectId, UUID tokenId, String tokenHash,
                           Optional<LoginAudit> loginAudit) implements Command<Boolean> {

    IssueSessionCommand(AuthSubjectType subjectType, UUID subjectId, UUID tokenId, String tokenHash) {
        this(subjectType, subjectId, tokenId, tokenHash, Optional.empty());
    }

    /** No Venue is in scope, so the category selects no venue rule (ADR-022 names none for account commands). */
    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
    }

    /** Only the new row is written; a customer's User row is share-locked in the body. */
    @Override
    public CommandScope scope() {
        return CommandScope.shared(LockSet.empty());
    }

    @Override
    public Boolean execute(CommandContext ctx) {
        if (subjectType == AuthSubjectType.CUSTOMER && !RefreshTokens.customerMayHoldSession(ctx, subjectId)) {
            return false;
        }
        RefreshTokens.insert(ctx, tokenId, subjectType, subjectId, tokenHash);
        loginAudit.ifPresent(audit -> audit.write(ctx));
        return true;
    }
}
