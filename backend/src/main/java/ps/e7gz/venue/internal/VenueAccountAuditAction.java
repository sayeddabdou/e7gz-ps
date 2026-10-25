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
    OWNER_PASSWORD_REFUSED(AuditPayloadAllowlist.of("protectedAction", "lockedOut")),
    /**
     * A protected action's Owner Password was accepted (FR-VEN-05, arch 10 §8.1), audited on its own row because the
     * action itself has none - the opening-hours change cancelling protected bookings (E7-113; no audit row for the
     * hours change itself, PO decision 3 Oct 2026). {@code protectedAction} is the matrix id; the row carries
     * owner_password_outcome = SUCCESS and is written in the action's transaction, so it exists only if the action
     * committed. Also the row the gate writes on its own when a protected action's password was right but the action
     * was then refused (PO decision Q2, 24 Oct 2026): {@code refusalCode} is then the refusal's API error code.
     */
    OWNER_PASSWORD_ACCEPTED(AuditPayloadAllowlist.of("protectedAction", "refusalCode")),
    /** The Platform Admin disabled Dashboard access (FR-ADM-03, FR-VEN-08); the admin's reason is required. */
    VENUE_ACCOUNT_DISABLED(AuditPayloadAllowlist.of("venueId", "previousStatus", "newStatus"), true),
    /** The Platform Admin re-enabled Dashboard access (FR-ADM-03); the admin's reason is required. */
    VENUE_ACCOUNT_ENABLED(AuditPayloadAllowlist.of("venueId", "previousStatus", "newStatus"), true),
    /** The Platform Admin set a new venue login password (FR-ADM-03); venue data is kept. */
    VENUE_LOGIN_PASSWORD_RESET(AuditPayloadAllowlist.of("venueId")),
    /** The Platform Admin set a new Owner Password (FR-ADM-03); the old one stopped working at once. */
    OWNER_PASSWORD_RESET(AuditPayloadAllowlist.of("venueId"));

    private final AuditPayloadAllowlist payload;
    private final boolean requiresReason;

    VenueAccountAuditAction(AuditPayloadAllowlist payload) {
        this(payload, false);
    }

    VenueAccountAuditAction(AuditPayloadAllowlist payload, boolean requiresReason) {
        this.payload = payload;
        this.requiresReason = requiresReason;
    }

    @Override
    public String subjectType() {
        return "VENUE_ACCOUNT";
    }

    @Override
    public AuditPayloadAllowlist payloadAllowlist() {
        return payload;
    }

    @Override
    public boolean requiresReason() {
        return requiresReason;
    }
}
