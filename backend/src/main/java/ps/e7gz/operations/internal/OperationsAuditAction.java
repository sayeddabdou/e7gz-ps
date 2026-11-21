package ps.e7gz.operations.internal;

import ps.e7gz.audit.api.AuditAction;
import ps.e7gz.audit.api.AuditPayloadAllowlist;

/**
 * Audit actions of the venue's Maintenance windows (FR-OPS-01: create, edit and delete are audited; FRS F-18 step 4)
 * and normal Venue Closures (FRS F-17, arch 10 §8.1). The actor is the Venue Account; a row written after the Owner
 * Password was checked carries that check (owner_password_outcome SUCCESS). No payload field ever holds a password.
 */
enum OperationsAuditAction implements AuditAction {

    /** A Maintenance window was created; {@code ownerPasswordUsed} is true when it was over the rolling threshold. */
    MAINTENANCE_CREATED("MAINTENANCE", AuditPayloadAllowlist.of("resourceId", "startAt", "endAt", "ownerPasswordUsed")),
    /** A Maintenance window was moved, extended, shortened or re-worded (FR-OPS-04). */
    MAINTENANCE_EDITED("MAINTENANCE", AuditPayloadAllowlist.of("resourceId", "startAt", "endAt", "previousStartAt",
            "previousEndAt", "ownerPasswordUsed")),
    /** A Maintenance window that had not ended was deleted (soft delete, v1.1.44). */
    MAINTENANCE_DELETED("MAINTENANCE", AuditPayloadAllowlist.of("resourceId", "startAt", "endAt")),
    /** A normal Closure was saved; {@code cancelledBookings} is how many bookings it cancelled (FR-VEN-30). */
    CLOSURE_CREATED("VENUE_CLOSURE", AuditPayloadAllowlist.of("kind", "startAt", "endAt", "cancelledBookings")),
    /** A Closure was edited; shrinking it restores nothing it cancelled. */
    CLOSURE_EDITED("VENUE_CLOSURE", AuditPayloadAllowlist.of("kind", "startAt", "endAt", "previousStartAt",
            "previousEndAt", "cancelledBookings")),
    /** A Closure that had not ended was deleted (soft delete, v1.1.44); bookings it cancelled stay cancelled. */
    CLOSURE_DELETED("VENUE_CLOSURE", AuditPayloadAllowlist.of("kind", "startAt", "endAt")),
    /**
     * E7-82: a venue Emergency Closure or a resource Emergency Outage was declared, starting now, with no Owner Password
     * (FR-OPS-06, FR-OPS-11); what its all-or-nothing cascade ended is counted (FR-OPS-09).
     */
    EMERGENCY_DECLARED("EMERGENCY", AuditPayloadAllowlist.of("scope", "resourceId", "declaredAt", "expectedEndAt",
            "cancelledBookings", "interruptedBookings", "cancelledTournaments", "pausedTimers")),
    /** E7-82: the expected end moved later and the newly affected reservations were cancelled (FR-OPS-22). */
    EMERGENCY_EXTENDED("EMERGENCY", AuditPayloadAllowlist.of("scope", "resourceId", "previousExpectedEndAt",
            "expectedEndAt", "cancelledBookings", "interruptedBookings", "cancelledTournaments", "pausedTimers")),
    /** E7-82: the expected end passed with the Emergency unresolved; the block stays (FR-OPS-18). */
    EMERGENCY_AWAITING_CONFIRMATION("EMERGENCY", AuditPayloadAllowlist.of("scope", "resourceId", "expectedEndAt")),
    /** E7-82: END EMERGENCY, the staff's audited assertion that the venue or resource is operational (FR-OPS-21). */
    EMERGENCY_ENDED("EMERGENCY", AuditPayloadAllowlist.of("scope", "resourceId", "endedAt")),
    /** E7-82: the Emergency was replaced by its target in one commit (FR-OPS-23, FR-OPS-24). */
    EMERGENCY_CONVERTED("EMERGENCY", AuditPayloadAllowlist.of("scope", "resourceId", "convertedTo", "targetId",
            "endedAt"));

    private final String subjectType;
    private final AuditPayloadAllowlist payload;

    OperationsAuditAction(String subjectType, AuditPayloadAllowlist payload) {
        this.subjectType = subjectType;
        this.payload = payload;
    }

    @Override
    public String subjectType() {
        return subjectType;
    }

    @Override
    public AuditPayloadAllowlist payloadAllowlist() {
        return payload;
    }
}
