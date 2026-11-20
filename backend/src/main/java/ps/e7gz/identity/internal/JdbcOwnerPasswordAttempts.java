package ps.e7gz.identity.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.RateLimiterType;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.identity.api.OwnerPasswordAttempts;

/**
 * {@link OwnerPasswordAttempts} on {@code iam.security_rate_limit_counter}. Two rows per venue account, both with
 * {@code subject_key_hash} = SHA-256 of the account id:
 * <ul>
 *   <li>{@code action_class = VENUE} - the short lockout (arch 10 §7: 5 per 15 minutes, then a 15-minute lockout,
 *       approved). Only wrong passwords count; the lockout is {@code cooldown_until}.</li>
 *   <li>{@code action_class = VENUE_LOCKOUTS} - the long lock (PCR-012, E7-121): {@code counter} is the short lockouts
 *       in a fixed 24-hour window starting at the first one counted (PO decision 17 Nov 2026, no migration); the third
 *       sets {@code cooldown_until} to {@code infinity}, which only {@link #endLocks} clears.</li>
 * </ul>
 */
@Component
class JdbcOwnerPasswordAttempts implements OwnerPasswordAttempts {

    static final String ACTION_CLASS = "VENUE";
    static final String LOCKOUTS_ACTION_CLASS = "VENUE_LOCKOUTS";

    @Override
    public Lock lock(CommandContext command, UUID venueAccountId) {
        List<String> locked = command.jdbc().sql("""
                        SELECT action_class FROM iam.security_rate_limit_counter
                         WHERE limiter_type = :limiter AND subject_key_hash = :key
                           AND action_class IN (:actionClass, :lockoutsActionClass) AND cooldown_until > :now""")
                .param("limiter", RateLimiterType.OWNER_PASSWORD.name())
                .param("key", subjectKeyHash(venueAccountId))
                .param("actionClass", ACTION_CLASS)
                .param("lockoutsActionClass", LOCKOUTS_ACTION_CLASS)
                .param("now", OtpChallenges.utc(command.commandNow()))
                .query(String.class).list();
        if (locked.contains(LOCKOUTS_ACTION_CLASS)) {
            return Lock.LONG_LOCKED;
        }
        return locked.isEmpty() ? Lock.NONE : Lock.LOCKED_OUT;
    }

    /**
     * One atomic upsert on {@code uq_security_rate_limit_key}: concurrent failures on one account serialise on the row.
     * A window that has run out, or a count the Platform Admin's reset set to 0, restarts at this failure with a count
     * of 1; reaching the limit sets the lockout. Only the failure whose count is exactly the limit started it: a later
     * one racing in before the lockout was seen is not a new lockout.
     */
    @Override
    public Failure recordFailure(CommandContext command, UUID venueAccountId) {
        int failures = command.jdbc().sql("""
                        INSERT INTO iam.security_rate_limit_counter AS c
                               (id, limiter_type, subject_key_hash, action_class, window_started_at, window_seconds,
                                counter, cooldown_until, updated_at)
                        VALUES (:id, :limiter, :key, :actionClass, :now, :windowSeconds, 1,
                                CASE WHEN 1 >= :max THEN CAST(:now AS timestamptz) + make_interval(secs => :lockoutSeconds)
                                END,
                                :now)
                        ON CONFLICT ON CONSTRAINT uq_security_rate_limit_key DO UPDATE SET
                               counter = CASE WHEN c.counter = 0
                                                   OR c.window_started_at + make_interval(secs => c.window_seconds)
                                                      <= EXCLUDED.window_started_at
                                              THEN 1 ELSE c.counter + 1 END,
                               window_started_at = CASE WHEN c.counter = 0
                                                             OR c.window_started_at + make_interval(secs => c.window_seconds)
                                                                <= EXCLUDED.window_started_at
                                                        THEN EXCLUDED.window_started_at ELSE c.window_started_at END,
                               window_seconds = EXCLUDED.window_seconds,
                               cooldown_until = CASE
                                   WHEN (CASE WHEN c.counter = 0
                                                   OR c.window_started_at + make_interval(secs => c.window_seconds)
                                                      <= EXCLUDED.window_started_at
                                              THEN 1 ELSE c.counter + 1 END) >= :max
                                   THEN EXCLUDED.window_started_at + make_interval(secs => :lockoutSeconds)
                                   ELSE c.cooldown_until END,
                               version = c.version + 1,
                               updated_at = EXCLUDED.updated_at
                        RETURNING counter
                        """)
                .param("id", UUID.randomUUID())
                .param("limiter", RateLimiterType.OWNER_PASSWORD.name())
                .param("key", subjectKeyHash(venueAccountId))
                .param("actionClass", ACTION_CLASS)
                .param("now", OtpChallenges.utc(command.commandNow()))
                .param("windowSeconds", Math.toIntExact(WINDOW.toSeconds()))
                .param("max", MAX_FAILURES)
                .param("lockoutSeconds", Math.toIntExact(LOCKOUT.toSeconds()))
                .query(Integer.class).single();
        if (failures != MAX_FAILURES) {
            return Failure.COUNTED;
        }
        return recordLockout(command, venueAccountId) == LOCKOUTS_BEFORE_LONG_LOCK
                ? Failure.LONG_LOCK_STARTED : Failure.LOCKED_OUT;
    }

    /**
     * PCR-012: one short lockout counted in the account's fixed 24-hour window (the same upsert shape as above); the
     * {@link #LOCKOUTS_BEFORE_LONG_LOCK}th sets {@code cooldown_until = infinity}.
     *
     * @return the lockouts now counted in the window
     */
    private int recordLockout(CommandContext command, UUID venueAccountId) {
        return command.jdbc().sql("""
                        INSERT INTO iam.security_rate_limit_counter AS c
                               (id, limiter_type, subject_key_hash, action_class, window_started_at, window_seconds,
                                counter, cooldown_until, updated_at)
                        VALUES (:id, :limiter, :key, :actionClass, :now, :windowSeconds, 1,
                                CASE WHEN 1 >= :max THEN CAST('infinity' AS timestamptz) END, :now)
                        ON CONFLICT ON CONSTRAINT uq_security_rate_limit_key DO UPDATE SET
                               counter = CASE WHEN c.counter = 0
                                                   OR c.window_started_at + make_interval(secs => c.window_seconds)
                                                      <= EXCLUDED.window_started_at
                                              THEN 1 ELSE c.counter + 1 END,
                               window_started_at = CASE WHEN c.counter = 0
                                                             OR c.window_started_at + make_interval(secs => c.window_seconds)
                                                                <= EXCLUDED.window_started_at
                                                        THEN EXCLUDED.window_started_at ELSE c.window_started_at END,
                               window_seconds = EXCLUDED.window_seconds,
                               cooldown_until = CASE
                                   WHEN (CASE WHEN c.counter = 0
                                                   OR c.window_started_at + make_interval(secs => c.window_seconds)
                                                      <= EXCLUDED.window_started_at
                                              THEN 1 ELSE c.counter + 1 END) >= :max
                                   THEN CAST('infinity' AS timestamptz)
                                   ELSE c.cooldown_until END,
                               version = c.version + 1,
                               updated_at = EXCLUDED.updated_at
                        RETURNING counter
                        """)
                .param("id", UUID.randomUUID())
                .param("limiter", RateLimiterType.OWNER_PASSWORD.name())
                .param("key", subjectKeyHash(venueAccountId))
                .param("actionClass", LOCKOUTS_ACTION_CLASS)
                .param("now", OtpChallenges.utc(command.commandNow()))
                .param("windowSeconds", Math.toIntExact(LONG_LOCK_WINDOW.toSeconds()))
                .param("max", LOCKOUTS_BEFORE_LONG_LOCK)
                .query(Integer.class).single();
    }

    /** Both rows back to a count of 0 with no lock; rows that do not exist yet stay absent. */
    @Override
    public void endLocks(CommandContext command, UUID venueAccountId) {
        command.jdbc().sql("""
                        UPDATE iam.security_rate_limit_counter
                           SET counter = 0, cooldown_until = NULL, version = version + 1, updated_at = :now
                         WHERE limiter_type = :limiter AND subject_key_hash = :key
                           AND action_class IN (:actionClass, :lockoutsActionClass)""")
                .param("limiter", RateLimiterType.OWNER_PASSWORD.name())
                .param("key", subjectKeyHash(venueAccountId))
                .param("actionClass", ACTION_CLASS)
                .param("lockoutsActionClass", LOCKOUTS_ACTION_CLASS)
                .param("now", OtpChallenges.utc(command.commandNow()))
                .update();
    }

    static String subjectKeyHash(UUID venueAccountId) {
        return Sha256.hex("venue-account:" + venueAccountId);
    }
}
