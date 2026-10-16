package ps.e7gz.identity.internal;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.identity.internal.IdentityAuditAction.LoginOutcome;

/**
 * One login audit row for a Platform Admin or venue account (E7-56): written in the command that issues the session
 * on success, or in a command of its own on a refusal. Customers are not audited.
 */
record LoginAudit(AuditWriter writer, AuthSubjectType audience, UUID accountId, LoginOutcome outcome) {

    /** Only the two staff audiences are audited; empty for a customer. */
    static Optional<LoginAudit> of(AuditWriter writer, AuthSubjectType audience, UUID accountId, LoginOutcome outcome) {
        return audience == AuthSubjectType.CUSTOMER ? Optional.empty()
                : Optional.of(new LoginAudit(writer, audience, accountId, outcome));
    }

    void write(CommandContext context) {
        boolean succeeded = outcome == LoginOutcome.SUCCEEDED;
        IdentityAuditAction action = switch (audience) {
            case ADMIN -> succeeded ? IdentityAuditAction.ADMIN_LOGIN_SUCCEEDED : IdentityAuditAction.ADMIN_LOGIN_FAILED;
            case VENUE -> succeeded ? IdentityAuditAction.VENUE_ACCOUNT_LOGIN_SUCCEEDED
                    : IdentityAuditAction.VENUE_ACCOUNT_LOGIN_FAILED;
            case CUSTOMER -> throw new IllegalStateException("Customer logins are not audited");
        };
        // The account itself on success; SYSTEM on a refusal - nobody was authenticated, and a disabled account
        // cannot be an actor (audit.guard_audit_insert)
        AuditActor actor = !succeeded ? AuditActor.system()
                : audience == AuthSubjectType.ADMIN ? AuditActor.admin(accountId) : AuditActor.venue(accountId);
        writer.write(context, AuditRecord.builder(action, actor, accountId, CorrelationId.require())
                .payload(Map.of("audience", audience.name(), "outcome", outcome.name()))
                .build());
    }
}
