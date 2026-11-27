package ps.e7gz.tournament.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.TournamentResourceRemovalCause;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.codes.TournamentTerminalReason;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.tournament.api.LiveTournament;
import ps.e7gz.tournament.api.TournamentCascades;

/**
 * The Tournament side of the operational cascades of other modules (E7-73; E7-94): Venue Emergency Closure, Resource
 * Emergency Outage and Owner Review find their Tournaments here and end or trim them.
 *
 * <p>E7-94 adds the resource-loss handler of arch 06 section 3.5 (FR-TRN-27) - the decision that used to sit in each
 * operations cascade - and the Creator's {@code TournamentCancelled} on every system cancellation, so each cancellation
 * path tells the Creator the same way (E7-92, E7-93). The reason given is always the highest condition in force at the
 * command instant ({@code tournament.highest_current_cancellation_reason}, FR-TRN-31); the database checks it again on
 * the write.
 */
@Component
class TournamentSystemCascades implements TournamentCascades {

    private final Tournaments tournaments;
    private final TournamentBoundaries boundaries;
    private final Outbox outbox;

    TournamentSystemCascades(Tournaments tournaments, TournamentBoundaries boundaries, Outbox outbox) {
        this.tournaments = tournaments;
        this.boundaries = boundaries;
        this.outbox = outbox;
    }

    @Override
    public List<LiveTournament> liveAtVenue(CommandContext command, UUID venueId, Instant from, Instant to) {
        return tournaments.liveAtVenue(command, venueId, from, to);
    }

    @Override
    public List<LiveTournament> liveOnResources(CommandContext command, Collection<UUID> resourceIds, Instant from,
                                                Instant to) {
        return tournaments.liveOnResources(command, resourceIds, from, to);
    }

    @Override
    public boolean cancel(CommandContext command, UUID tournamentId, TournamentTerminalReason reason) {
        Objects.requireNonNull(command, "command");
        Tournaments.Current current = tournaments.current(command, tournamentId)
                .orElseThrow(() -> new IllegalArgumentException("No Tournament " + tournamentId));
        return cancelled(command, current, reason);
    }

    @Override
    public ResourceLoss loseResource(CommandContext command, UUID tournamentId, UUID resourceId,
                                     TournamentResourceRemovalCause cause) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(tournamentId, "tournamentId");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(cause, "cause");
        if (tournaments.current(command, tournamentId).isEmpty()) {
            throw new IllegalArgumentException("No Tournament " + tournamentId);
        }
        // a due checkpoint or start owns the next transition, decided as of its own instant (ADR-023 item 5)
        boundaries.settle(command, tournamentId);
        Tournaments.Current tournament = tournaments.current(command, tournamentId).orElseThrow();
        if (!tournament.live() || !tournaments.liveResources(command, tournamentId).contains(resourceId)) {
            return ResourceLoss.NOT_AFFECTED;
        }
        Instant now = command.commandNow();
        TournamentTerminalReason resourceReason = TournamentTerminalReason.valueOf(cause.name());

        if (tournament.status() != TournamentStatus.ACTIVE) {
            // before the start ANY lost Resource cancels the whole Tournament, PENDING_VENUE_APPROVAL included
            TournamentTerminalReason reason = tournaments.conditionInForce(command, tournamentId, tournament.status(),
                    now).orElse(resourceReason);
            return cancelled(command, tournament, reason) ? ResourceLoss.CANCELLED : ResourceLoss.NOT_AFFECTED;
        }

        // ACTIVE: the Resource goes, its hold released; the stored venue cost stays as it is (FR-TRN-28)
        tournaments.removeResource(command, tournamentId, resourceId, cause);
        Optional<TournamentTerminalReason> noneLeft = tournaments.conditionInForce(command, tournamentId,
                TournamentStatus.ACTIVE, now);
        if (noneLeft.isPresent()) {
            return cancelled(command, tournament, noneLeft.get()) ? ResourceLoss.CANCELLED : ResourceLoss.NOT_AFFECTED;
        }
        outbox.publish(command, TournamentAnswerEvents.resourceAvailabilityChanged(tournament, resourceId, cause,
                tournaments.liveResources(command, tournamentId)));
        return ResourceLoss.CONTINUES;
    }

    /** SYSTEM, holds released now, the Creator of a Player-hosted Tournament told; false when already terminal. */
    private boolean cancelled(CommandContext command, Tournaments.Current tournament,
                              TournamentTerminalReason reason) {
        Optional<Integer> sequence = tournaments.cancelled(command, tournament.id(), reason, AuditActor.system(),
                command.commandNow());
        if (sequence.isPresent() && tournament.playerHosted()) {
            outbox.publish(command, TournamentAnswerEvents.cancelled(tournament, sequence.get(), reason,
                    ActorType.SYSTEM));
        }
        return sequence.isPresent();
    }
}
