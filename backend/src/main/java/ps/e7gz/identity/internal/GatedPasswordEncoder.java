package ps.e7gz.identity.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.security.crypto.password.PasswordEncoder;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;

/**
 * The one bounded gate in front of every Argon2id hash and check (Sprint 0 review S-2, E7-24): login, registration,
 * the Owner Password, admin bootstrap and later password reset all use this bean. Each call needs a turn; a call that
 * gets none within the timeout fails with {@code RATE_LIMITED} before anything is hashed, so the caller writes nothing.
 */
final class GatedPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final Semaphore turns;
    private final Duration acquireTimeout;

    GatedPasswordEncoder(PasswordEncoder delegate, int maxConcurrent, Duration acquireTimeout) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.turns = new Semaphore(maxConcurrent, true);
        this.acquireTimeout = Objects.requireNonNull(acquireTimeout, "acquireTimeout");
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return withTurn(() -> delegate.encode(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return withTurn(() -> delegate.matches(rawPassword, encodedPassword));
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);           // reads the stored parameters only; no hashing
    }

    private <T> T withTurn(Supplier<T> hashing) {
        boolean acquired;
        try {
            acquired = turns.tryAcquire(acquireTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiException(ApiErrorCode.RATE_LIMITED);
        }
        if (!acquired) {
            throw new ApiException(ApiErrorCode.RATE_LIMITED);
        }
        try {
            return hashing.get();
        } finally {
            turns.release();
        }
    }
}
