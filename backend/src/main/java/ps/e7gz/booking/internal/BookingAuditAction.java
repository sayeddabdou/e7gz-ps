package ps.e7gz.booking.internal;

import ps.e7gz.audit.api.AuditAction;
import ps.e7gz.audit.api.AuditPayloadAllowlist;

/**
 * Audit actions owned by Booking. The constant name is the persisted {@code audit_event.action}; the subject is the
 * booking. No payload: the row's structured columns carry the transition, the venue and the Owner Password outcome,
 * never the password (NFR-21).
 */
enum BookingAuditAction implements AuditAction {

    /**
     * The venue cancelled a CONFIRMED Online Booking with the Owner Password (FR-VEN-03, E7-114): actor the venue
     * account, previous / new state CONFIRMED / CANCELLED, owner_password_outcome = SUCCESS (FR-VEN-05). Written in the
     * cancellation's transaction, so it exists only if the cancellation committed; a refused Owner Password is audited
     * by the Owner Password gate.
     */
    VENUE_CANCELLED_CONFIRMED_BOOKING;

    @Override
    public String subjectType() {
        return "BOOKING";
    }

    @Override
    public AuditPayloadAllowlist payloadAllowlist() {
        return AuditPayloadAllowlist.none();
    }
}
