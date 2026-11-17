package ps.e7gz.trust.internal;

import ps.e7gz.audit.api.AuditAction;
import ps.e7gz.audit.api.AuditPayloadAllowlist;

/**
 * The Platform Admin's Verified Player moderation actions (E7-72; Spec v5 sections 8.8 and 51.5). The constant name is
 * the persisted {@code audit_event.action} and equals the Admin's Trust input event type, as
 * {@code trust.assert_admin_trust_event_audit} requires: subject USER, actor the Admin, at the command instant, with the
 * reason the Product requires for every moderation action (section 8.8).
 */
enum VerifiedPlayerAuditAction implements AuditAction {

    VERIFIED_PLAYER_SUSPENDED,
    VERIFIED_PLAYER_REVOKED,
    VERIFIED_PLAYER_RESTORED;

    @Override
    public String subjectType() {
        return "USER";
    }

    @Override
    public AuditPayloadAllowlist payloadAllowlist() {
        return AuditPayloadAllowlist.none();
    }

    @Override
    public boolean requiresReason() {
        return true;
    }
}
