package ps.e7gz.venue.internal;

import ps.e7gz.audit.api.AuditAction;
import ps.e7gz.audit.api.AuditPayloadAllowlist;

/**
 * Audit actions of the venue account and its Owner Password (FR-VEN-05, FR-VEN-46, arch 10 §8.1 "Owner
 * authorization"). No payload field ever holds a password or a hash (NFR-21).
 */
enum VenueAccountAuditAction implements AuditAction {

    /** The Platform Admin created the venue's shared account and its first Owner Password (FR-ADM-02). */
    VENUE_ACCOUNT_CREATED(AuditPayloadAllowlist.of("venueId")),
    /** The venue changed its Owner Password; the old one stopped working at once (FR-VEN-46). */
    OWNER_PASSWORD_CHANGED(AuditPayloadAllowlist.of("venueId")),
    /**
     * A protected action was refused for its Owner Password (FR-VEN-05): {@code protectedAction} is the action id from
     * the Owner Password matrix, {@code lockedOut} tells whether the account was already locked out (password not
     * checked). The row carries owner_password_outcome = FAILURE.
     */
    OWNER_PASSWORD_REFUSED(AuditPayloadAllowlist.of("protectedAction", "lockedOut"));

    private final AuditPayloadAllowlist payload;

    VenueAccountAuditAction(AuditPayloadAllowlist payload) {
        this.payload = payload;
    }

    @Override
    public String subjectType() {
        return "VENUE_ACCOUNT";
    }

    @Override
    public AuditPayloadAllowlist payloadAllowlist() {
        return payload;
    }
}
