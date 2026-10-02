package ps.e7gz.geography.internal;

import ps.e7gz.audit.api.AuditAction;
import ps.e7gz.audit.api.AuditPayloadAllowlist;

/**
 * Audit actions owned by Geography. Area activation and deactivation are audited (FR-VEN-12, FR-VEN-47); creating and
 * renaming an area are not (Product Owner decision 2 Oct 2026, E7-23).
 */
enum GeographyAuditAction implements AuditAction {

    AREA_ACTIVATED,
    AREA_DEACTIVATED;

    @Override
    public String subjectType() {
        return "AREA";
    }

    @Override
    public AuditPayloadAllowlist payloadAllowlist() {
        return AuditPayloadAllowlist.none();
    }
}
