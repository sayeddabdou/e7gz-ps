package ps.e7gz.trust.internal;

import ps.e7gz.audit.api.AuditAction;
import ps.e7gz.audit.api.AuditPayloadAllowlist;

/**
 * Audit actions owned by Trust (E7-71). Subject: the banned User; actor: SYSTEM - a Ban is the consequence of replay,
 * never of a person's request. {@code BAN_EXTENDED} and {@code BAN_CORRECTED} carry exactly the fields
 * {@code trust.assert_ban_lifecycle_audit} matches at commit (FR-TRS-22: instance, triggering qualification and event,
 * previous and new stage, old and new end; FR-TRS-24: the replay-invalidating correction); activation and expiry are
 * recorded too, so every historical instance stays in audit (FR-TRS-23). Instants are ISO-8601 UTC strings.
 */
enum TrustAuditAction implements AuditAction {

    BAN_ACTIVATED(AuditPayloadAllowlist.of("banInstanceId", "qualificationId", "triggeringEventId", "penaltyStage",
            "endsAt")),
    BAN_EXTENDED(AuditPayloadAllowlist.of("banInstanceId", "triggeringQualificationId", "triggeringEventId",
            "previousPenaltyStage", "newPenaltyStage", "oldEndsAt", "newEndsAt")),
    BAN_EXPIRED(AuditPayloadAllowlist.of("banInstanceId", "penaltyStage", "endsAt")),
    BAN_CORRECTED(AuditPayloadAllowlist.of("banInstanceId", "correctionEventId", "penaltyStage"));

    private final AuditPayloadAllowlist payload;

    TrustAuditAction(AuditPayloadAllowlist payload) {
        this.payload = payload;
    }

    @Override
    public String subjectType() {
        return "USER";
    }

    @Override
    public AuditPayloadAllowlist payloadAllowlist() {
        return payload;
    }

    /** {@code trust.assert_ban_lifecycle_audit}: a correction ending a Ban needs a non-blank reason. */
    @Override
    public boolean requiresReason() {
        return this == BAN_CORRECTED;
    }
}
