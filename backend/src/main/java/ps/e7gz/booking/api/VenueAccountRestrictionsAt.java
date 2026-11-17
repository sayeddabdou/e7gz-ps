package ps.e7gz.booking.api;

import java.time.Instant;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: whether a Venue Account was DISABLED at a past instant, from the immutable restriction history
 * {@code iam.venue_account_restriction_interval} (Spec v5 section 24.3 rung 4, {@code VENUE_ACCOUNT_DISABLED}; E7-118).
 * A late T6 decides from the state at {@code startAt}, never at the time it runs (arch 08 section 2.2), and the live
 * {@code iam.venue_account.account_status} only says what holds now. Declared here because Attendance owns the table
 * (arch 03 section 1, module 11) and already depends on Booking; Attendance implements it (CLAUDE.md section 3: a port
 * in the caller's {@code api}, no module cycle).
 */
public interface VenueAccountRestrictionsAt {

    /**
     * A restriction interval of the Venue in force at {@code at}: {@code disabled_at <= at} and not re-enabled at
     * {@code at} ({@code reenabled_at} NULL or after it) - the same half-open semantics as the other as-of reads of
     * {@code operations.api.OperationalStateAt}. On the command's own transaction.
     */
    boolean disabledAt(CommandContext command, UUID venueId, Instant at);
}
