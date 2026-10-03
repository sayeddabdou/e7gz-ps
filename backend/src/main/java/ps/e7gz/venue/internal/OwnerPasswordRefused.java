package ps.e7gz.venue.internal;

import java.util.UUID;

/**
 * Aborts a protected command whose Owner Password was refused, so its transaction rolls back with nothing written.
 * Only {@link JdbcOwnerPasswordGate#run} catches it; it never reaches the API. Carries no password.
 */
final class OwnerPasswordRefused extends RuntimeException {

    private final UUID venueAccountId;
    private final boolean lockedOut;

    OwnerPasswordRefused(UUID venueAccountId, boolean lockedOut) {
        super(lockedOut ? "Owner Password attempts locked out" : "Owner Password does not match", null, false, false);
        this.venueAccountId = venueAccountId;
        this.lockedOut = lockedOut;
    }

    UUID venueAccountId() {
        return venueAccountId;
    }

    /** True when the account was already locked out, so the password was not checked. */
    boolean lockedOut() {
        return lockedOut;
    }
}
