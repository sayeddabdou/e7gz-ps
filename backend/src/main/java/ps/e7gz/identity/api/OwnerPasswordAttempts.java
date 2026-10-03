package ps.e7gz.identity.api;

import java.time.Duration;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * The Owner Password attempt limit per venue account (arch 10 §3.4 and §7, NFR-16): at most {@link #MAX_FAILURES}
 * wrong passwords within {@link #WINDOW}, then the account's protected actions are refused for {@link #LOCKOUT}. Counted
 * in {@code iam.security_rate_limit_counter} with {@code limiter_type = OWNER_PASSWORD}, on the caller's command
 * transaction. The counter stores only a hash of the account id, never a password.
 */
public interface OwnerPasswordAttempts {

    int MAX_FAILURES = 5;
    Duration WINDOW = Duration.ofMinutes(15);
    Duration LOCKOUT = Duration.ofMinutes(15);

    /** True while the account is locked out at {@link CommandContext#commandNow()}. */
    boolean lockedOut(CommandContext command, UUID venueAccountId);

    /**
     * Counts one wrong Owner Password. The {@link #MAX_FAILURES}th failure inside the window starts the lockout.
     *
     * @return true when the account is now locked out
     */
    boolean recordFailure(CommandContext command, UUID venueAccountId);
}
