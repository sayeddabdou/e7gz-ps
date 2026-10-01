package ps.e7gz.identity.internal;

import java.time.Duration;
import java.util.UUID;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.RateLimiterType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;

/**
 * Counts one login attempt in {@code iam.security_rate_limit_counter} (arch 10 section 7, arch 01 section 14 A10) and
 * returns the count in the current window, this attempt included.
 *
 * <p>One atomic upsert on {@code uq_security_rate_limit_key}: concurrent attempts on one key serialise on the row, so
 * none is lost and none sees a stale count. A window that has run out restarts at this attempt with a count of 1.
 */
record CountLoginAttemptCommand(UUID newRowId, AuthSubjectType actionClass, String subjectKeyHash, Duration window)
        implements Command<Integer> {

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
    public Integer execute(CommandContext ctx) {
        return ctx.jdbc().sql("""
                        INSERT INTO iam.security_rate_limit_counter AS c
                               (id, limiter_type, subject_key_hash, action_class, window_started_at, window_seconds,
                                counter, updated_at)
                        VALUES (:id, :limiter, :key, :actionClass, :now, :windowSeconds, 1, :now)
                        ON CONFLICT ON CONSTRAINT uq_security_rate_limit_key DO UPDATE SET
                               counter = CASE WHEN c.window_started_at + make_interval(secs => c.window_seconds)
                                                   <= EXCLUDED.window_started_at
                                              THEN 1 ELSE c.counter + 1 END,
                               window_started_at = CASE WHEN c.window_started_at + make_interval(secs => c.window_seconds)
                                                             <= EXCLUDED.window_started_at
                                                        THEN EXCLUDED.window_started_at ELSE c.window_started_at END,
                               window_seconds = EXCLUDED.window_seconds,
                               version = c.version + 1,
                               updated_at = EXCLUDED.updated_at
                        RETURNING counter
                        """)
                .param("id", newRowId)
                .param("limiter", RateLimiterType.LOGIN.name())
                .param("key", subjectKeyHash)
                .param("actionClass", actionClass.name())
                .param("now", OtpChallenges.utc(ctx.commandNow()))
                .param("windowSeconds", Math.toIntExact(window.toSeconds()))
                .query(Integer.class)
                .single();
    }
}
