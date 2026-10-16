package ps.e7gz.identity.internal;

import ps.e7gz.audit.api.AuditAction;
import ps.e7gz.audit.api.AuditPayloadAllowlist;

/**
 * Login audit of the two staff audiences (Sprint 0 review; E7-56, names approved by the PO on 15 Oct 2026). The subject
 * is the account; the payload is the audience and the outcome only - never the identifier, the password or the IP.
 * A failed login for an identifier that matches no account is not audited: there is no account to be the subject, and
 * the login rate limiter covers it (PO decision 15 Oct 2026). Customer logins are not audited.
 */
enum IdentityAuditAction implements AuditAction {

    /** A Platform Admin signed in. Actor: that admin. */
    ADMIN_LOGIN_SUCCEEDED("ADMIN"),
    /** A Platform Admin account's login was refused (wrong password, or the account may not sign in). Actor: SYSTEM. */
    ADMIN_LOGIN_FAILED("ADMIN"),
    /** The venue account signed in. Actor: that venue account. */
    VENUE_ACCOUNT_LOGIN_SUCCEEDED("VENUE_ACCOUNT"),
    /** A venue account's login was refused (wrong password, or the account is disabled). Actor: SYSTEM. */
    VENUE_ACCOUNT_LOGIN_FAILED("VENUE_ACCOUNT");

    /** {@code outcome}: {@link LoginOutcome}. */
    private static final AuditPayloadAllowlist PAYLOAD = AuditPayloadAllowlist.of("audience", "outcome");

    private final String subjectType;

    IdentityAuditAction(String subjectType) {
        this.subjectType = subjectType;
    }

    @Override
    public String subjectType() {
        return subjectType;
    }

    @Override
    public AuditPayloadAllowlist payloadAllowlist() {
        return PAYLOAD;
    }

    /** The audited outcome of one login attempt for a known account. */
    enum LoginOutcome {
        SUCCEEDED,
        WRONG_PASSWORD,
        ACCOUNT_NOT_ALLOWED,
        /** The account's login is paused by the per-account limit (E7-57); the password was not the reason. */
        LOGIN_PAUSED
    }
}
