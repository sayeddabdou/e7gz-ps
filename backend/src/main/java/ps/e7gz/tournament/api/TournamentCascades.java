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
     * <p>E7-94: a Player-hosted Tournament's Creator is told in the same transaction
     * ({@code TournamentCancelled}, actor SYSTEM), as on every other cancellation path; the participants' notifications
     * are E7-89's.
     *
     * @return {@code true} when this call cancelled it; {@code false} when it was already terminal (nothing changes)
     * @throws IllegalArgumentException when no such Tournament exists
     */
    boolean cancel(CommandContext command, UUID tournamentId, TournamentTerminalReason reason);

    /**
     * E7-94, the resource-loss handler (arch 06 section 3.5, FR-TRN-27, FR-OPS-12, FR-OPS-28): an assigned Resource is
     * lost to a Resource Emergency Outage or an Owner Review, whose row the caller has already written in this command.
     * <ul>
     *   <li><b>Before the start</b> - any state, PENDING_VENUE_APPROVAL included - the whole Tournament is
     *       {@link ResourceLoss#CANCELLED} under the highest reason in force (FR-TRN-31): the resource reason, or a
     *       higher one.</li>
     *   <li><b>ACTIVE</b>: the assignment is marked removed at {@link CommandContext#commandNow()} with {@code cause}
     *       and its hold released. While at least one operational Resource remains - the database's own count
     *       ({@code tournament.highest_current_cancellation_reason}) - the Tournament {@link ResourceLoss#CONTINUES} and
     *       {@code TournamentResourceAvailabilityChanged} is written for its Creator and Participants; with none left it
     *       is {@link ResourceLoss#CANCELLED} under the resource reason.</li>
     * </ul>
     * The stored venue cost is never recomputed (FR-TRN-28): only the assignment and its hold change. A boundary that is
     * already due (checkpoint, start) is resolved first, as of its own instant, so the loss meets the state the
     * Tournament is really in. Running it again for the same Resource changes nothing.
     *
     * @return what happened to the Tournament; {@link ResourceLoss#NOT_AFFECTED} when it is terminal, or the Resource
     *         is not (or no longer) assigned to it
     * @throws IllegalArgumentException when no such Tournament exists
     */
    ResourceLoss loseResource(CommandContext command, UUID tournamentId, UUID resourceId,
                              TournamentResourceRemovalCause cause);

    /** The outcome of {@link #loseResource}. */
    enum ResourceLoss {

        /** The Tournament was CANCELLED by this loss. */
        CANCELLED,

        /** An ACTIVE Tournament lost the Resource and goes on with the others. */
        CONTINUES,

        /** Nothing changed: the Tournament is terminal, or the Resource is not assigned to it. */
        NOT_AFFECTED
    }
}
