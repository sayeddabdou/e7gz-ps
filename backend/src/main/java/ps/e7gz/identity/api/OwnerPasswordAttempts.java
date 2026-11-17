package ps.e7gz.identity.api;

import java.time.Duration;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * The Owner Password attempt limits per venue account, on the caller's command transaction, in
 * {@code iam.security_rate_limit_counter} with {@code limiter_type = OWNER_PASSWORD}. The counter stores only a hash of
 * the account id, never a password.
 * <ul>
 *   <li>The short lockout (arch 10 §3.4 and §7, NFR-16): at most {@link #MAX_FAILURES} wrong passwords within
 *       {@link #WINDOW}, then the account's protected actions are refused for {@link #LOCKOUT}.</li>
 *   <li>The long lock (PCR-012, E7-121): {@link #LOCKOUTS_BEFORE_LONG_LOCK} short lockouts within
 *       {@link #LONG_LOCK_WINDOW} lock the protected actions until the Platform Admin resets the Owner Password
 *       ({@link #endLocks}); time never ends it. The lockouts are counted in fixed windows of 24 hours, each starting at
 *       the first lockout counted in it, in the existing table, with no migration (PO decision 17 Nov 2026).</li>
 * </ul>
 */
public interface OwnerPasswordAttempts {

    int MAX_FAILURES = 5;
    Duration WINDOW = Duration.ofMinutes(15);
    Duration LOCKOUT = Duration.ofMinutes(15);

    /** PCR-012 item 2 (the Product Owner's numbers, 15 Nov 2026): fixed Product rules, not configuration. */
    int LOCKOUTS_BEFORE_LONG_LOCK = 3;
    Duration LONG_LOCK_WINDOW = Duration.ofHours(24);

    /** Where the account's protected actions stand at {@link CommandContext#commandNow()}. */
    enum Lock {
        /** The password is checked. */
        NONE,
        /** The short lockout: refused without checking the password, until it runs out. */
        LOCKED_OUT,
        /** The long lock (PCR-012): refused without checking the password, until the Platform Admin's reset. */
        LONG_LOCKED
    }

    /** What one counted wrong password did. */
    enum Failure {
        /** Counted; the account is not locked. */
        COUNTED,
        /** This failure started a short lockout. */
        LOCKED_OUT,
        /** This failure started a short lockout, and that lockout started the long lock (PCR-012). */
        LONG_LOCK_STARTED
    }

    /** The account's lock at the command instant; the long lock wins over a short lockout still running. */
    Lock lock(CommandContext command, UUID venueAccountId);

    /**
     * Counts one wrong Owner Password. The {@link #MAX_FAILURES}th failure inside the window starts a short lockout,
     * which is counted towards the long lock; the {@link #LOCKOUTS_BEFORE_LONG_LOCK}th lockout inside its window starts
     * the long lock. Each is reported by the one failure that started it.
     */
    Failure recordFailure(CommandContext command, UUID venueAccountId);

    /**
     * The Platform Admin reset the Owner Password (FR-ADM-03; PCR-012 item 3): the long lock and a short lockout still
     * running both end, and both counts start again from zero (PO decision 17 Nov 2026).
     */
    void endLocks(CommandContext command, UUID venueAccountId);
}
