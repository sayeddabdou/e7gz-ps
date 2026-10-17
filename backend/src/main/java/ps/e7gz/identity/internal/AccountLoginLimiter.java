package ps.e7gz.identity.internal;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.RateLimiterType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.identity.api.SecurityAlerts;

/**
 * The per-account login limit (Sprint 0 review S-7, PO decision 2 Oct 2026; E7-57): next to the (identifier + IP)
 * limit of {@link LoginRateLimiter}, failed logins of one account are counted across every IP - 50 in one hour pause
 * that account's login for 15 minutes, in all three audiences. In {@code iam.security_rate_limit_counter}:
 * {@code limiter_type = LOGIN}, {@code action_class} = {@code CUSTOMER_ACCOUNT} / {@code VENUE_ACCOUNT} /
 * {@code ADMIN_ACCOUNT}, {@code subject_key_hash} = SHA-256 of the account id; {@code cooldown_until} ends the pause.
 *
 * <p>Only a wrong password for a known account counts (an unknown identifier has no account). The pause starts a new
 * window, so after it the account gets 50 more tries in the hour. The 50th failure raises one SEV_3
 * {@code ACCOUNT_LOGIN_LIMIT_REACHED} admin alert (PO decision 15 Oct 2026) through {@link SecurityAlerts}. Fails
 * closed like the other limiter: a counter that cannot be read or written throws, and the login stops there.
 */
@Component
class AccountLoginLimiter {

    static final int MAX_FAILURES = 50;
    static final Duration WINDOW = Duration.ofHours(1);
    static final Duration PAUSE = Duration.ofMinutes(15);

    private final CommandRunner runner;
    private final JdbcClient jdbc;
    private final List<SecurityAlerts> securityAlerts;

    AccountLoginLimiter(CommandRunner runner, DataSource appDataSource, ObjectProvider<SecurityAlerts> securityAlerts) {
        this.runner = runner;
        this.jdbc = JdbcClient.create(appDataSource);
        this.securityAlerts = securityAlerts.orderedStream().toList();
    }

    /** {@code action_class} of the account counters: a closed set of three, recorded in the change report. */
    static String actionClass(AuthSubjectType audience) {
        return audience.name() + "_ACCOUNT";
    }

    static String keyHash(UUID accountId) {
        return Sha256.hex("account:" + accountId);
    }

    /** Whether the account's login is paused now, by the database clock the pause was set with. */
    boolean paused(AuthSubjectType audience, UUID accountId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT cooldown_until > now() FROM iam.security_rate_limit_counter
                         WHERE limiter_type = :limiter AND subject_key_hash = :key AND action_class = :actionClass""")
                .param("limiter", RateLimiterType.LOGIN.name())
                .param("key", keyHash(accountId))
                .param("actionClass", actionClass(audience))
                .query(Boolean.class).optional().orElse(false));
    }

    /** One failed login of the account; the 50th in the window pauses it and raises the alert. */
    void countFailure(AuthSubjectType audience, UUID accountId) {
        boolean pausedNow = runner.run(new CountFailure(UUID.randomUUID(), actionClass(audience), keyHash(accountId)));
        if (pausedNow) {
            securityAlerts.forEach(alerts -> alerts.accountLoginLimitReached(audience, accountId));
        }
    }

    /**
     * One atomic upsert on {@code uq_security_rate_limit_key}, as {@link CountLoginAttemptCommand}: a window that ran
     * out restarts at this failure; the 50th failure sets {@code cooldown_until} and restarts the window. Returns
     * whether this failure started the pause.
     */
    private record CountFailure(UUID newRowId, String actionClass, String keyHash) implements Command<Boolean> {

        /** No Venue is in scope, so the category selects no venue rule (ADR-022 names none for account commands). */
        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
        }

        /** The counter row is the lock: the upsert takes it. */
        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public Boolean execute(CommandContext ctx) {
            return ctx.jdbc().sql("""
                            INSERT INTO iam.security_rate_limit_counter AS c
                                   (id, limiter_type, subject_key_hash, action_class, window_started_at, window_seconds,
                                    counter, updated_at)
                            VALUES (:id, :limiter, :key, :actionClass, :now, :windowSeconds, 1, :now)
                            ON CONFLICT ON CONSTRAINT uq_security_rate_limit_key DO UPDATE SET
                                   counter = CASE
                                       WHEN c.window_started_at + make_interval(secs => c.window_seconds)
                                            <= EXCLUDED.window_started_at THEN 1
                                       WHEN c.counter + 1 >= :max THEN 0
                                       ELSE c.counter + 1 END,
                                   window_started_at = CASE
                                       WHEN c.window_started_at + make_interval(secs => c.window_seconds)
                                            <= EXCLUDED.window_started_at OR c.counter + 1 >= :max
                                       THEN EXCLUDED.window_started_at ELSE c.window_started_at END,
                                   cooldown_until = CASE
                                       WHEN c.window_started_at + make_interval(secs => c.window_seconds)
                                            > EXCLUDED.window_started_at AND c.counter + 1 >= :max
                                       THEN CAST(:now AS timestamptz) + make_interval(secs => :pauseSeconds)
                                       ELSE c.cooldown_until END,
                                   window_seconds = EXCLUDED.window_seconds,
                                   version = c.version + 1,
                                   updated_at = EXCLUDED.updated_at
                            RETURNING cooldown_until IS NOT NULL AND cooldown_until = CAST(:now AS timestamptz)
                                      + make_interval(secs => :pauseSeconds)
                            """)
                    .param("id", newRowId)
                    .param("limiter", RateLimiterType.LOGIN.name())
                    .param("key", keyHash)
                    .param("actionClass", actionClass)
                    .param("now", OtpChallenges.utc(ctx.commandNow()))
                    .param("windowSeconds", (int) WINDOW.toSeconds())
                    .param("max", MAX_FAILURES)
                    .param("pauseSeconds", (int) PAUSE.toSeconds())
                    .query(Boolean.class).single();
        }
    }
}
