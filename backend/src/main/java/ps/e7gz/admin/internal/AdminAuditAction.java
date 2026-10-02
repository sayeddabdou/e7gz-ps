package ps.e7gz.admin.internal;

import ps.e7gz.audit.api.AuditAction;
import ps.e7gz.audit.api.AuditPayloadAllowlist;

/**
 * Audit actions owned by Platform Administration. The constant name is the persisted {@code audit_event.action}.
 */
enum AdminAuditAction implements AuditAction {

    /**
     * The one-off creation of the first Platform Admin (Sprint 0 review S-5, Product Owner decision 2 Oct 2026, E7-23).
     * The subject is the new admin; the actor is SYSTEM, because no admin existed to act. No payload: the e-mail is
     * personal data and the password never leaves the hash.
     */
    FIRST_ADMIN_CREATED("ADMIN", AuditPayloadAllowlist.none());

    private final String subjectType;
    private final AuditPayloadAllowlist payloadAllowlist;

    AdminAuditAction(String subjectType, AuditPayloadAllowlist payloadAllowlist) {
        this.subjectType = subjectType;
        this.payloadAllowlist = payloadAllowlist;
    }

    @Override
    public String subjectType() {
        return subjectType;
    }

    @Override
    public AuditPayloadAllowlist payloadAllowlist() {
        return payloadAllowlist;
    }
}
