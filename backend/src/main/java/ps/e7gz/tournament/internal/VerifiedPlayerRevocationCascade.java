package ps.e7gz.tournament.internal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.codes.TournamentTerminalReason;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.trust.api.VerifiedPlayerTournamentCascade;

/**
 * O15, the Verified Player cascade (E7-94; arch 06 section 3.6, FR-TRN-20, Spec v5 sections 8.9 and 37.8): Trust's
 * {@link VerifiedPlayerTournamentCascade}, called in the command that wrote a {@code VERIFIED -> REVOKED} transition.
 * <ul>
 *   <li>The Creator's Player-hosted Tournaments still in {@code PENDING_VENUE_APPROVAL} or {@code WAITING_FOR_PLAYERS}
 *       become {@code CANCELLED} by SYSTEM, their holds released and timers withdrawn, and the Creator is told
 *       ({@code TournamentCancelled}).</li>
 *   <li>{@code CONFIRMED} and {@code ACTIVE} Tournaments remain. SUSPENDED never reaches here (Trust calls this only for
 *       a revocation), and a later restoration never resurrects a cancelled Tournament: nothing here, or anywhere,
 *       moves a terminal Tournament.</li>
 * </ul>
 * <b>Once per transition.</b> Trust records the cascade under the transition's own key in the same command
 * ({@code VP_TOURNAMENT_CASCADE:{transitionId}}, unique, with {@code cascade_executed_at}) and the database proves it at
 * commit ({@code tournament.assert_verified_player_tournament_cascade}). This side is idempotent too: once its
 * Tournaments are cancelled, a second call for the same transition finds none and changes nothing.
 *
 * <p><b>Reason priority</b> (FR-TRN-31): a boundary that is already due is resolved first, as of its own instant - an
 * approval deadline or a checkpoint the worker has not reached yet decides the Tournament as it was then, before the
 * revocation. What is still pending or waiting is cancelled under the highest condition in force now:
 * {@code CREATOR_VERIFIED_STATUS_REVOKED}, unless a higher one (an Owner Review, an Emergency, a disabled Venue
 * Account) also holds.
 *
 * <p>The command must name the Creator in its lock set: {@code begin_command} then locks every Tournament the Creator
 * created (the Admin and trust-correction commands that revoke do).
 */
@Component
class VerifiedPlayerRevocationCascade implements VerifiedPlayerTournamentCascade {

    /** Section 8.9: what a revocation cancels. CONFIRMED and ACTIVE remain. */
    static final String CANCELLABLE = """
            SELECT t.id
              FROM tournament.tournament t
             WHERE t.host_type = 'PLAYER' AND t.creator_user_id = :creator
               AND t.status IN ('PENDING_VENUE_APPROVAL', 'WAITING_FOR_PLAYERS')
             ORDER BY t.id
            """;

    private static final List<TournamentStatus> CANCELLABLE_STATES = List.of(TournamentStatus.PENDING_VENUE_APPROVAL,
            TournamentStatus.WAITING_FOR_PLAYERS);

    private final Tournaments tournaments;
    private final TournamentBoundaries boundaries;
    private final Outbox outbox;

    VerifiedPlayerRevocationCascade(Tournaments tournaments, TournamentBoundaries boundaries, Outbox outbox) {
        this.tournaments = tournaments;
        this.boundaries = boundaries;
        this.outbox = outbox;
    }

    @Override
    public void cancelPendingHostedTournaments(CommandContext command, UUID creatorUserId, UUID transitionId) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(creatorUserId, "creatorUserId");
        Objects.requireNonNull(transitionId, "transitionId");
        List<UUID> ids = command.jdbc().sql(CANCELLABLE).param("creator", creatorUserId).query(UUID.class).list();
        for (UUID id : ids) {
            boundaries.settle(command, id);
            Optional<Tournaments.Current> found = tournaments.current(command, id)
                    .filter(t -> CANCELLABLE_STATES.contains(t.status()));
            if (found.isEmpty()) {
                continue;                                         // a due boundary decided it first (FR-TRN-31)
            }
            Tournaments.Current tournament = found.get();
            TournamentTerminalReason reason = tournaments.conditionInForce(command, id, tournament.status(),
                            command.commandNow())
                    .orElseThrow(() -> new IllegalStateException("Verified Player transition " + transitionId
                            + " is not a revocation in force for Tournament " + id));
            int sequence = tournaments.cancelled(command, id, reason, AuditActor.system(), command.commandNow())
                    .orElseThrow(() -> new IllegalStateException("Tournament " + id + " ended under its lock"));
            outbox.publish(command, TournamentAnswerEvents.cancelled(tournament, sequence, reason, ActorType.SYSTEM));
        }
    }
}
