package ps.e7gz.identity.internal;

import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.RateLimiterType;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.identity.api.OwnerPasswordAttempts;

/**
 * {@link OwnerPasswordAttempts} on {@code iam.security_rate_limit_counter} (arch 10 §7: 5 per 15 minutes, then a
 * 15-minute lockout, approved). One row per venue account: {@code subject_key_hash} = SHA-256 of the account id,
 * {@code action_class = VENUE}. Only wrong passwords count; the lockout is {@code cooldown_until}.
 */
@Component
class JdbcOwnerPasswordAttempts implements OwnerPasswordAttempts {

    static final String ACTION_CLASS = "VENUE";

    @Override
    public boolean lockedOut(CommandContext command, UUID venueAccountId) {
        return command.jdbc().sql("""
                        SELECT EXISTS (SELECT 1 FROM iam.security_rate_limit_counter
                                        WHERE limiter_type = :limiter AND subject_key_hash = :key
                                          AND action_class = :actionClass AND cooldown_until > :now)""")
                .param("limiter", RateLimiterType.OWNER_PASSWORD.name())
                .param("key", subjectKeyHash(venueAccountId))
                .param("actionClass", ACTION_CLASS)
                .param("now", OtpChallenges.utc(command.commandNow()))
                .query(Boolean.class).single();
    }

    /**
     * One atomic upsert on {@code uq_security_rate_limit_key}: concurrent failures on one account serialise on the row.
     * A window that has run out restarts at this failure with a count of 1; reaching the limit sets the lockout.
     */
    @Override
    public boolean recordFailure(CommandContext command, UUID venueAccountId) {
        return command.jdbc().sql("""
                        INSERT INTO iam.security_rate_limit_counter AS c
                               (id, limiter_type, subject_key_hash, action_class, window_started_at, window_seconds,
                                counter, cooldown_until, updated_at)
                        VALUES (:id, :limiter, :key, :actionClass, :now, :windowSeconds, 1,
                                CASE WHEN 1 >= :max THEN CAST(:now AS timestamptz) + make_interval(secs => :lockoutSeconds)
                                END,
                                :now)
                        ON CONFLICT ON CONSTRAINT uq_security_rate_limit_key DO UPDATE SET
                               counter = CASE WHEN c.window_started_at + make_interval(secs => c.window_seconds)
                                                   <= EXCLUDED.window_started_at
                                              THEN 1 ELSE c.counter + 1 END,
                               window_started_at = CASE WHEN c.window_started_at + make_interval(secs => c.window_seconds)
                                                             <= EXCLUDED.window_started_at
                                                        THEN EXCLUDED.window_started_at ELSE c.window_started_at END,
                               window_seconds = EXCLUDED.window_seconds,
                               cooldown_until = CASE
                                   WHEN (CASE WHEN c.window_started_at + make_interval(secs => c.window_seconds)
                                                   <= EXCLUDED.window_started_at
                                              THEN 1 ELSE c.counter + 1 END) >= :max
                                   THEN EXCLUDED.window_started_at + make_interval(secs => :lockoutSeconds)
                                   ELSE c.cooldown_until END,
                               version = c.version + 1,
                               updated_at = EXCLUDED.updated_at
                        RETURNING cooldown_until IS NOT NULL AND cooldown_until > :now
                        """)
                .param("id", UUID.randomUUID())
                .param("limiter", RateLimiterType.OWNER_PASSWORD.name())
                .param("key", subjectKeyHash(venueAccountId))
                .param("actionClass", ACTION_CLASS)
                .param("now", OtpChallenges.utc(command.commandNow()))
                .param("windowSeconds", Math.toIntExact(WINDOW.toSeconds()))
                .param("max", MAX_FAILURES)
                .param("lockoutSeconds", Math.toIntExact(LOCKOUT.toSeconds()))
                .query(Boolean.class).single();
    }

    static String subjectKeyHash(UUID venueAccountId) {
        return Sha256.hex("venue-account:" + venueAccountId);
    }
}
