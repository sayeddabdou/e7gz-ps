package ps.e7gz.venue.internal;

import java.util.Objects;
import java.util.UUID;
import ps.e7gz.identity.api.OwnerPasswordAttempts.Lock;

/**
 * Aborts a protected command whose Owner Password was refused, so its transaction rolls back with nothing written.
 * Only {@link JdbcOwnerPasswordGate#run} catches it; it never reaches the API. Carries no password.
 */
final class OwnerPasswordRefused extends RuntimeException {

    private final UUID venueAccountId;
    private final Lock lock;

    /** @param lock {@link Lock#NONE} for a wrong password; otherwise the lock that refused it unchecked */
    OwnerPasswordRefused(UUID venueAccountId, Lock lock) {
        super(lock == Lock.NONE ? "Owner Password does not match" : "Owner Password attempts locked: " + lock, null,
                false, false);
        this.venueAccountId = venueAccountId;
        this.lock = Objects.requireNonNull(lock, "lock");
    }

    UUID venueAccountId() {
        return venueAccountId;
    }

    /** The lock that refused it, or {@link Lock#NONE} for a wrong password. */
    Lock lock() {
        return lock;
    }

    /** True when the account was already locked (short or long), so the password was not checked. */
    boolean lockedOut() {
        return lock != Lock.NONE;
    }
}
