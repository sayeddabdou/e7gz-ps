package ps.e7gz.identity.internal;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
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
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.security.ActiveSubjectCheck;
import ps.e7gz.identity.api.LoginAccountDirectory;
import ps.e7gz.identity.api.LoginAccountDirectory.LoginAccount;
import ps.e7gz.identity.api.SecurityAlerts;
import ps.e7gz.identity.internal.IdentityAuditAction.LoginOutcome;

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
    private final AccountLoginLimiter accountLimiter;
    private final JdbcClient jdbc;
    private final Map<AuthSubjectType, LoginAccountDirectory> directories = new EnumMap<>(AuthSubjectType.class);
    private final Map<AuthSubjectType, ActiveSubjectCheck> activeChecks = new EnumMap<>(AuthSubjectType.class);
    /** Verified in place of a missing account, so an unknown identifier costs the same as a wrong password. */
    private final String decoyHash;
    private final AuditWriter audit;
    /** The Platform Admin side (E7-56); none in a context without it. */
    private final List<SecurityAlerts> securityAlerts;

    AuthSessions(CommandRunner runner, PasswordEncoder passwords, AccessTokens accessTokens, LoginRateLimiter rateLimiter,
                 AccountLoginLimiter accountLimiter,
                 DataSource appDataSource, ObjectProvider<LoginAccountDirectory> directories,
                 ObjectProvider<ActiveSubjectCheck> activeChecks, AuditWriter audit,
                 ObjectProvider<SecurityAlerts> securityAlerts) {
        this.runner = runner;
        this.passwords = passwords;
        this.accessTokens = accessTokens;
        this.rateLimiter = rateLimiter;
        this.accountLimiter = accountLimiter;
        this.jdbc = JdbcClient.create(appDataSource);
        directories.orderedStream().forEach(d -> register(this.directories, d.subjectType(), d));
        activeChecks.orderedStream().forEach(c -> register(this.activeChecks, c.subjectType(), c));
        this.decoyHash = passwords.encode(RefreshTokens.newRawToken());
        this.audit = audit;
        this.securityAlerts = securityAlerts.orderedStream().toList();
    }

    /** A new session: the access JWT and the raw refresh token, which exists only in this response. */
    public record TokenPair(String accessToken, String refreshToken) {

        @Override
        public String toString() {
            return "TokenPair[<redacted>]";
        }
    }

    /** How long a refresh token of this type lives (arch 10 section 2.3); the web session cookie's Max-Age. */
    public static Duration refreshLifetime(AuthSubjectType type) {
        return RefreshTokens.lifetime(type);
    }

    /**
     * @throws ApiException {@code RATE_LIMITED} beyond 10 attempts in 15 minutes, or while the account's login is
     *         paused after 50 failures in an hour (S-7, E7-57), {@code UNAUTHENTICATED} for an
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
        if (account.isEmpty()) {
            throw new ApiException(ApiErrorCode.UNAUTHENTICATED);       // not audited: no account (E7-56)
        }
        UUID accountId = account.get().id();
        // S-7 (E7-57): a paused account is refused whatever the password - checked after the hash, so a paused account
        // costs the same time as any other
        if (accountLimiter.paused(audience, accountId)) {
            auditRefusal(audience, accountId, LoginOutcome.LOGIN_PAUSED);
            throw new ApiException(ApiErrorCode.RATE_LIMITED);
        }
        if (!passwordMatches) {
            accountLimiter.countFailure(audience, accountId);
            auditRefusal(audience, accountId, LoginOutcome.WRONG_PASSWORD);
            throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
        }
        if (!account.get().active()) {
            auditRefusal(audience, accountId, LoginOutcome.ACCOUNT_NOT_ALLOWED);
            throw new ApiException(ApiErrorCode.FORBIDDEN);                              // FR-VEN-08
        }
        return start(audience, accountId, LoginAudit.of(audit, audience, accountId, LoginOutcome.SUCCEEDED))
                .orElseThrow(() -> new ApiException(ApiErrorCode.UNAUTHENTICATED));
    }

    /**
     * Starts a session for an identity already proven by other means (phone verification, arch 09 section 2). Empty
     * when a customer was anonymised meanwhile.
     */
    public Optional<TokenPair> start(AuthSubjectType type, UUID subjectId) {
        return start(type, subjectId, Optional.empty());
    }

    /** {@link #start}, with a staff login's success audit row in the same command (E7-56). */
    private Optional<TokenPair> start(AuthSubjectType type, UUID subjectId, Optional<LoginAudit> loginAudit) {
        String refreshToken = RefreshTokens.newRawToken();
        boolean issued = runner.run(new IssueSessionCommand(type, subjectId, UUID.randomUUID(),
                RefreshTokens.hash(refreshToken), loginAudit));
        return issued ? Optional.of(new TokenPair(accessTokens.issue(type, subjectId), refreshToken)) : Optional.empty();
    }

    /**
     * The audit row of a refused staff login for a known account (E7-56): audience and outcome only. A failing audit is
     * logged, and the login is still refused with its own answer.
     */
    private void auditRefusal(AuthSubjectType audience, UUID accountId, LoginOutcome outcome) {
        LoginAudit.of(audit, audience, accountId, outcome).ifPresent(refusal -> {
            try {
                runner.run(new RecordLoginRefusalCommand(refusal));
            } catch (RuntimeException failure) {
                log.error("Login refusal audit failed (subjectType={}, subjectId={})", audience, accountId, failure);
            }
        });
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
                securityAlerts.forEach(alerts -> alerts.refreshTokenReused(audience, reused.subjectId())); // E7-56
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
