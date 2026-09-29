package ps.e7gz.audit;

import ps.e7gz.audit.api.AuditAction;
import ps.e7gz.audit.api.AuditPayloadAllowlist;

/**
 * Audit actions used only by the audit tests. The real action codes are declared by the modules that own them (the
 * canonical list is v5.0 section 53.3, referenced by FR-AUD-03).
 */
public enum TestAuditAction implements AuditAction {

    E7_17_TEST_RESOURCE_NOTE("RESOURCE", AuditPayloadAllowlist.of(
            "resourceName", "window.startAt", "window.endAt", "bookingIds[]", "items[].id")),

    E7_17_TEST_NO_PAYLOAD("BOOKING", AuditPayloadAllowlist.none()),

    E7_17_TEST_REASON_REQUIRED("BOOKING", AuditPayloadAllowlist.none(), true);

    private final String subjectType;
    private final AuditPayloadAllowlist payloadAllowlist;
    private final boolean requiresReason;

    TestAuditAction(String subjectType, AuditPayloadAllowlist payloadAllowlist) {
        this(subjectType, payloadAllowlist, false);
    }

    TestAuditAction(String subjectType, AuditPayloadAllowlist payloadAllowlist, boolean requiresReason) {
        this.subjectType = subjectType;
        this.payloadAllowlist = payloadAllowlist;
        this.requiresReason = requiresReason;
    }

    @Override
    public String subjectType() {
        return subjectType;
    }

    @Override
    public AuditPayloadAllowlist payloadAllowlist() {
        return payloadAllowlist;
    }

    @Override
    public boolean requiresReason() {
        return requiresReason;
    }
}
