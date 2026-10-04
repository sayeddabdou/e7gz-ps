package ps.e7gz.resource.internal;

import ps.e7gz.audit.api.AuditAction;
import ps.e7gz.audit.api.AuditPayloadAllowlist;

/**
 * Resource changes (arch 10 §8.1 "resource state changes; console change", Product Spec §13.7). A venue action's row
 * carries the successful Owner Password check (FR-VEN-05); a refused one is audited by the Owner Password gate.
 */
enum ResourceAuditAction implements AuditAction {

    /** Added by the Platform Admin during onboarding or by the venue; the name is assigned (FR-VEN-41). */
    RESOURCE_CREATED(AuditPayloadAllowlist.of("venueId", "activity", "name", "consoleType")),
    RESOURCE_DISABLED(AuditPayloadAllowlist.of("venueId", "previousState", "newState")),
    RESOURCE_RE_ENABLED(AuditPayloadAllowlist.of("venueId", "previousState", "newState")),
    RESOURCE_ARCHIVED(AuditPayloadAllowlist.of("venueId", "previousState", "newState")),
    RESOURCE_RESTORED(AuditPayloadAllowlist.of("venueId", "previousState", "newState")),
    /** PS4 ↔ PS5; the room's price overrides were cleared (FR-VEN-15). */
    RESOURCE_CONSOLE_CHANGED(AuditPayloadAllowlist.of("venueId", "previousConsole", "newConsole"));

    private final AuditPayloadAllowlist payload;

    ResourceAuditAction(AuditPayloadAllowlist payload) {
        this.payload = payload;
    }

    @Override
    public String subjectType() {
        return "RESOURCE";
    }

    @Override
    public AuditPayloadAllowlist payloadAllowlist() {
        return payload;
    }
}
