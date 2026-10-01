package ps.e7gz.identity.internal;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.security.ActiveSubjectCheck;
import ps.e7gz.identity.api.LoginAccountDirectory;
import ps.e7gz.identity.api.LoginAccountDirectory.LoginAccount;

/**
 * Login, refresh and logout for the three identity types (arch 10 section 2, arch 09 section 2). Each session is a
 * 15-minute access JWT plus a rotating refresh token.
 *
 * <p>Failures are deliberately coarse: an unknown identifier and a wrong password are the same 401
 * {@code UNAUTHENTICATED}, after the same Argon2id work; only a correct password on a venue or admin account that may
 * not log in gets 403 {@code FORBIDDEN} (FR-VEN-08). Every refresh failure - unknown, expired, revoked, reused or
 * another audience's token - is 401 {@code UNAUTHENTICATED}.
 */
@Service
public class AuthSessions {

    private static final Logger log = LoggerFactory.getLogger(AuthSessions.class);

    private final CommandRunner runner;
    private final PasswordEncoder passwords;
    private final AccessTokens accessTokens;
    private final LoginRateLimiter rateLimiter;
    private final JdbcClient jdbc;
    private final Map<AuthSubjectType, LoginAccountDirectory> directories = new EnumMap<>(AuthSubjectType.class);
    private final Map<AuthSubjectType, ActiveSubjectCheck> activeChecks = new EnumMap<>(AuthSubjectType.class);
    /** Verified in place of a missing account, so an unknown identifier costs the same as a wrong password. */
    private final String decoyHash;

    AuthSessions(CommandRunner runner, PasswordEncoder passwords, AccessTokens accessTokens, LoginRateLimiter rateLimiter,
                 DataSource appDataSource, ObjectProvider<LoginAccountDirectory> directories,
                 ObjectProvider<ActiveSubjectCheck> activeChecks) {
        this.runner = runner;
        this.passwords = passwords;
        this.accessTokens = accessTokens;
        this.rateLimiter = rateLimiter;
        this.jdbc = JdbcClient.create(appDataSource);
        directories.orderedStream().forEach(d -> register(this.directories, d.subjectType(), d));
        activeChecks.orderedStream().forEach(c -> register(this.activeChecks, c.subjectType(), c));
        this.decoyHash = passwords.encode(RefreshTokens.newRawToken());
    }

    /** A new session: the access JWT and the raw refresh token, which exists only in this response. */
    public record TokenPair(String accessToken, String refreshToken) {

        @Override
        public String toString() {
            return "TokenPair[<redacted>]";
        }
    }

    /**
     * @throws ApiException {@code RATE_LIMITED} beyond 10 attempts in 15 minutes, {@code UNAUTHENTICATED} for an
     *         unknown identifier or a wrong password, {@code FORBIDDEN} for a venue or admin account that may not log in
     */
    public TokenPair login(AuthSubjectType audience, String loginIdentifier, String password, String clientIp) {
        LoginAccountDirectory directory = directories.get(audience);
        String normalised = directory != null ? directory.normalise(loginIdentifier)
                : LoginAccountDirectory.trimmedCaseInsensitive(loginIdentifier);
        rateLimiter.countAttempt(audience, normalised, clientIp);           // before any credential is looked at

        // The original identifier: a phone-looking username must still match as a username.
        Optional<LoginAccount> account = directory == null ? Optional.empty() : directory.find(loginIdentifier);
        // An anonymised customer has an empty hash and is not found anyway; either way the decoy is verified instead.
        String hash = account.map(LoginAccount::passwordHash).filter(h -> !h.isEmpty()).orElse(decoyHash);
        boolean passwordMatches = passwords.matches(password, hash);
        if (account.isEmpty() || !passwordMatches) {
            throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
        }
        if (!account.get().active()) {
            throw new ApiException(ApiErrorCode.FORBIDDEN);                              // FR-VEN-08
        }
        return start(audience, account.get().id())
                .orElseThrow(() -> new ApiException(ApiErrorCode.UNAUTHENTICATED));
    }

    /**
     * Starts a session for an identity already proven by other means (phone verification, arch 09 section 2). Empty
     * when a customer was anonymised meanwhile.
     */
    public Optional<TokenPair> start(AuthSubjectType type, UUID subjectId) {
        String refreshToken = RefreshTokens.newRawToken();
        boolean issued = runner.run(
                new IssueSessionCommand(type, subjectId, UUID.randomUUID(), RefreshTokens.hash(refreshToken)));
        return issued ? Optional.of(new TokenPair(accessTokens.issue(type, subjectId), refreshToken)) : Optional.empty();
    }

    /**
     * Rotates the refresh token (arch 10 section 2.3).
     *
     * @throws ApiException {@code UNAUTHENTICATED} for an unknown, expired, revoked or reused token, a token of
     *         another audience, or an identity that may no longer hold a session
     */
    public TokenPair refresh(AuthSubjectType audience, String refreshToken) {
        String presented = RefreshTokens.hash(refreshToken);
        UUID holder = holderOf(presented, audience).orElseThrow(() -> new ApiException(ApiErrorCode.UNAUTHENTICATED));
        // Read from the owning module now, but applied by the command only after reuse detection: a reused token
        // revokes its chain even when the account has meanwhile been disabled (arch 10 section 2.3, FR-VEN-08).
        ActiveSubjectCheck check = activeChecks.get(audience);
        boolean holderActive = check != null && check.isActive(holder);
        String next = RefreshTokens.newRawToken();
        RotateRefreshTokenCommand.Outcome outcome = runner.run(new RotateRefreshTokenCommand(
                audience, presented, holder, holderActive, UUID.randomUUID(), RefreshTokens.hash(next)));
        return switch (outcome) {
            case RotateRefreshTokenCommand.Rotated rotated ->
                    new TokenPair(accessTokens.issue(audience, rotated.subjectId()), next);
            case RotateRefreshTokenCommand.Reused reused -> {
                log.warn("Refresh token reuse: token chain revoked (subjectType={}, subjectId={})",
                        audience, reused.subjectId());
                throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
            }
            case RotateRefreshTokenCommand.Refused refused -> throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
        };
    }

    /** Revokes the session of this refresh token. Unknown tokens and other audiences' tokens change nothing. */
    public void logout(AuthSubjectType audience, String refreshToken) {
        runner.run(new RevokeRefreshTokenCommand(audience, RefreshTokens.hash(refreshToken)));
    }

    /** Whose token this is, if it belongs to this audience; the command re-reads it under its row lock. */
    private Optional<UUID> holderOf(String tokenHash, AuthSubjectType audience) {
        return jdbc.sql("SELECT subject_id FROM iam.auth_refresh_token WHERE token_hash = :hash AND subject_type = :type")
                .param("hash", tokenHash)
                .param("type", audience.name())
                .query(UUID.class)
                .optional();
    }

    private static <T> void register(Map<AuthSubjectType, T> map, AuthSubjectType type, T bean) {
        if (map.putIfAbsent(type, bean) != null) {
            throw new IllegalStateException("Two beans for " + type + " (" + bean.getClass().getName() + ")");
        }
    }
}
