package ps.e7gz.venue.internal;

import ps.e7gz.audit.api.AuditAction;
import ps.e7gz.audit.api.AuditPayloadAllowlist;

/**
 * Audit actions owned by the Venue module. Every marketplace status change is audited with the admin's reason and the
 * previous and new status (arch 01 section 14 A9, FR-AUD-06). Creating and editing a venue are not audited (Product
 * Owner decision 2 Oct 2026, E7-23).
 */
enum VenueAuditAction implements AuditAction {

    VENUE_MARKETPLACE_STATUS_CHANGED;

    @Override
    public String subjectType() {
        return "VENUE";
    }

    @Override
    public AuditPayloadAllowlist payloadAllowlist() {
        return AuditPayloadAllowlist.of("previousStatus", "newStatus");
    }

    @Override
    public boolean requiresReason() {
        return true;
    }
}
