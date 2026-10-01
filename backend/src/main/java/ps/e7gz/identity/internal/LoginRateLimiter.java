package ps.e7gz.identity.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;

/**
 * The login limit: 10 attempts per 15 minutes per identity (arch 10 section 7, approved 26 Sep 2026, arch 01
 * section 14 A4), counted in PostgreSQL with {@code limiter_type = LOGIN}, {@code action_class} = the audience and
 * {@code subject_key_hash} = SHA-256 of the normalised identifier and the client IP (A10). Every attempt counts,
 * whatever its outcome, and is committed before the password is checked.
 *
 * <p>Fails closed (A10): a counter that cannot be read or written throws, and the login stops there.
 */
@Component
class LoginRateLimiter {

    static final int MAX_ATTEMPTS = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final CommandRunner runner;

    LoginRateLimiter(CommandRunner runner) {
        this.runner = runner;
    }

    /** @throws ApiException {@code RATE_LIMITED} once this attempt is beyond the limit */
    void countAttempt(AuthSubjectType audience, String normalisedIdentifier, String clientIp) {
        int attempts = runner.run(new CountLoginAttemptCommand(
                UUID.randomUUID(), audience, subjectKeyHash(normalisedIdentifier, clientIp), WINDOW));
        if (attempts > MAX_ATTEMPTS) {
            throw new ApiException(ApiErrorCode.RATE_LIMITED);
        }
    }

    /**
     * SHA-256 of an unambiguous encoding of (identifier, IP): the identifier is length-prefixed, so no identifier can
     * run into the IP. The raw identifier is never stored.
     */
    static String subjectKeyHash(String normalisedIdentifier, String clientIp) {
        Objects.requireNonNull(normalisedIdentifier, "normalisedIdentifier");
        Objects.requireNonNull(clientIp, "clientIp");
        return Sha256.hex(normalisedIdentifier.length() + ":" + normalisedIdentifier + "|" + clientIp);
    }
}
