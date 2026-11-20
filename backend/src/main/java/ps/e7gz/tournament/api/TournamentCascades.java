package ps.e7gz.tournament.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import ps.e7gz.common.codes.TournamentResourceRemovalCause;
import ps.e7gz.common.codes.TournamentTerminalReason;
import ps.e7gz.common.command.CommandContext;

/**
 * What the system cascades of other modules - Venue Emergency Closure, Resource Emergency Outage, Owner Review, Venue
 * Account disable, Verified Player revocation - need from the Tournament module: find the live Tournaments a cause
 * reaches, and cancel them with the cause's reason, releasing their holds (E7-73; roadmap section 7 dependency note,
 * arch 06 sections 2.5, 2.7, 2.8 and 3.6).
 *
 * <p>Every method runs on the caller's command transaction ({@link CommandContext#jdbc()}), opened by
 * {@code tournament.begin_command}. A Tournament is written only when its id is in the command's
 * {@code LockSet.tournaments()}; the database refuses anything else with {@code 40001} (arch 04 section 2.1). The usual
 * way: find the ids with a read-only command first ({@code LockSet.empty()}), name them in the cascade's lock set, then
 * find them again under the locks - the second answer is the one to act on.
 *
 * <p>The database decides every rule: the reason priority (FR-TRN-31, {@code tournament.highest_current_cancellation_reason}),
 * the state graph and the deferred aggregate checks at {@code COMMIT}. This module does not repeat them; a refused write
 * propagates as the database raised it.
 */
public interface TournamentCascades {

    /**
     * The live Tournaments of the Venue whose interval {@code [startAt, endAt)} overlaps {@code [from, to)}, in start
     * order. For a Venue Emergency Closure or a Venue Account disable.
     */
    List<LiveTournament> liveAtVenue(CommandContext command, UUID venueId, Instant from, Instant to);

    /**
     * The live Tournaments that still have one of the Resources assigned ({@code removed_at IS NULL}) and whose interval
     * overlaps {@code [from, to)}, in start order. For a Resource Emergency Outage or an Owner Review.
     */
    List<LiveTournament> liveOnResources(CommandContext command, Collection<UUID> resourceIds, Instant from,
                                         Instant to);

    /**
     * Cancels the Tournament as a system consequence (actor {@code SYSTEM}, no Owner Password, arch 06 section 3.3):
     * {@code CANCELLED} with {@code reason}, one history row and its audit event, every still-active hold released at
     * {@link CommandContext#commandNow()} with the reason as release reason, and its pending Tournament timers
     * superseded. The cause behind the reason (the Owner Review, Emergency, disabled account or revoked status) must
     * already be written in this command: the database accepts a condition-driven reason only when it is the highest
     * cause in force (FR-TRN-31).
     *
     * <p>No notification is sent here: the cascade that cancels notifies the people it affects (arch 06 section 2.7).
     *
     * @return {@code true} when this call cancelled it; {@code false} when it was already terminal (nothing changes)
     * @throws IllegalArgumentException when no such Tournament exists
     */
    boolean cancel(CommandContext command, UUID tournamentId, TournamentTerminalReason reason);

    /**
     * E7-82 (FR-TRN-27, FR-OPS-12): an ACTIVE Tournament loses one Resource to a Resource Emergency Outage (or an Owner
     * Review, E7-84) and continues on the others: the assignment is marked removed at {@link CommandContext#commandNow()}
     * with {@code cause}, and its hold is released with the cause as release reason. A Tournament left with no operational
     * Resource must then be cancelled by the caller ({@link #cancel}); the database refuses the commit otherwise
     * ({@code operations.assert_emergency_cascade_complete}).
     *
     * @return {@code false}, changing nothing, when the Resource is not (or no longer) assigned to it
     */
    boolean removeResource(CommandContext command, UUID tournamentId, UUID resourceId,
                           TournamentResourceRemovalCause cause);
}
