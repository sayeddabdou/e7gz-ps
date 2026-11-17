package ps.e7gz.trust.internal;

import ps.e7gz.audit.api.AuditAction;
import ps.e7gz.audit.api.AuditPayloadAllowlist;

/**
 * Audit actions owned by Trust (E7-71). Subject: the User. Actor: SYSTEM for what replay derives - activation,
 * extension, expiry and a correction that follows an attendance correction; ADMIN for the Platform Admin's own trust
 * commands - the trust adjustment, a lift, a shortening and a correction in an Admin command (FR-TRS-27, FR-AUD-06),
 * each with the Admin's reason, which {@code trust.assert_ban_lifecycle_audit} and
 * {@code trust.assert_admin_trust_event_audit} require. {@code BAN_EXTENDED} and {@code BAN_CORRECTED} carry exactly the fields
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
    BAN_CORRECTED(AuditPayloadAllowlist.of("banInstanceId", "correctionEventId", "penaltyStage")),
    /** FR-TRS-27: the ADMIN_TRUST_ADJUSTMENT v1 absolute values, with the event it bound. */
    ADMIN_TRUST_ADJUSTMENT(AuditPayloadAllowlist.of("trustEventId", "counterAfter", "penaltyStageAfter",
            "counterBefore", "penaltyStageBefore")),
    /** FR-TRS-23, FR-TRS-27: ends now. */
    BAN_LIFTED(AuditPayloadAllowlist.of("banInstanceId", "penaltyStage", "previousEndsAt")),
    /** FR-TRS-25, FR-TRS-27: {@code newEndsAt} is what the database matches. */
    BAN_SHORTENED(AuditPayloadAllowlist.of("banInstanceId", "penaltyStage", "previousEndsAt", "newEndsAt",
            "correctionEventId"));

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

    /** The database's audit guards: a correction, an Admin trust command and every Admin Ban change need a reason. */
    @Override
    public boolean requiresReason() {
        return this == BAN_CORRECTED || this == ADMIN_TRUST_ADJUSTMENT || this == BAN_LIFTED || this == BAN_SHORTENED;
    }
}
