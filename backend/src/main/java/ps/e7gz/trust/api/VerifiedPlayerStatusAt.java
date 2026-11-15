package ps.e7gz.trust.api;

import java.time.Instant;
import java.util.UUID;
import ps.e7gz.common.codes.VerifiedPlayerStatus;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: the "held privileges then" as-of query of E7-69 (arch 07 section 3, FR-TRS-06): the User's operational
 * Verified Player status at a past instant, read from {@code trust.verified_player_transition} - the operational
 * history - and never inferred from the replayed qualification, which may be corrected later (TrustReplay). Creating
 * transitions is E7-72's; this only reads them.
 */
public interface VerifiedPlayerStatusAt {

    /**
     * The {@code to_status} of the User's latest transition that occurred at or before {@code at} (latest
     * {@code occurred_at}, then the row id, as {@code tournament.highest_current_cancellation_reason} reads it);
     * {@link VerifiedPlayerStatus#NOT_VERIFIED} when there is none. Reads on the command's own transaction.
     */
    VerifiedPlayerStatus statusAt(CommandContext command, UUID userId, Instant at);
}
