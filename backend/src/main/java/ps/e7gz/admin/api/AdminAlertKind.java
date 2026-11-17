package ps.e7gz.admin.api;

/**
 * The alert kinds the application raises (E7-56; PO decisions 12 and 15 Oct 2026). Stored as its name in the open-text
 * {@code admin.admin_alert.kind}; the database's own abuse signal ({@code TOURNAMENT_CANCELLATION_ABUSE}) is raised by
 * its trigger and is not listed here.
 */
public enum AdminAlertKind {

    /** FR-BOK-28, NFR-29, arch 12 section 6.2: inventory contradicts the resource state at startAt (raised by E7-60). */
    SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR,
    /** E7-47 D11: a booking command found a broken reservation or Late Confirm invariant and rolled back. */
    BOOKING_INTEGRITY_FAILURE,
    /** arch 11 section 4: the nightly invariant sweep found a violation (E7-32, E7-40). */
    INVARIANT_VIOLATION,
    /** NFR-13, arch 12 section 6.3: an outbox event reached DEAD_LETTER (E7-41). */
    OUTBOX_DEAD_LETTER,
    /** arch 10 section 2.3: a rotated refresh token was used again and its whole chain was revoked. */
    REFRESH_TOKEN_REUSE,
    /** An account reached the per-account login limit (raised by E7-57). */
    ACCOUNT_LOGIN_LIMIT_REACHED,
    /**
     * Spec v5 section 52.6 "Verified Player Revoked" (FR-NTF-10): a VERIFIED -> REVOKED transition, by an admin or by a
     * trust correction (raised by E7-72; PO decision 19 Oct 2026: the admin alert inbox only).
     */
    ADMIN_VERIFIED_PLAYER_REVOKED,
    /**
     * Product v5.0 section 52.6 "User Temporary Ban Activated": a replay threshold crossing activated a Ban (raised by
     * E7-71 in the activating command). The admin alert inbox only - no per-admin notification (PO decision 20 Oct 2026).
     */
    ADMIN_USER_BAN_ACTIVATED
}
