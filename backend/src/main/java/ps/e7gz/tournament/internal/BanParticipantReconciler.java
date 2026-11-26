package ps.e7gz.tournament.internal;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.TournamentParticipantStatus;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.trust.api.ActiveBans;
import ps.e7gz.trust.api.TournamentParticipantCauses;

/**
 * The participant cause reconciler for O14 (E7-93; ADR-023 items 3 and 5, arch 06 section 3.7, FR-TRN-26), Trust's
 * {@link TournamentParticipantCauses}: after a Ban change, each of the User's participations in a non-terminal
 * Tournament follows the Ban in force at the command instant.
 * <ul>
 *   <li>a Ban in force and the row JOINED: INELIGIBLE (the guard records BAN, or ACCOUNT_DELETED when the account is
 *       gone) - also in an ACTIVE Tournament, where it can never come back;</li>
 *   <li>no Ban in force and the row INELIGIBLE/BAN: JOINED again, but only while the Tournament is pre-start and
 *       {@code now < startAt} - the reserved slot, never a new one. At or after {@code startAt} the row stays.</li>
 * </ul>
 * Each Tournament's due boundaries are settled first ({@link TournamentBoundaries#settle}): a checkpoint or start
 * already due owns the next transition and is decided as of its own instant, with its restorations; only then may the
 * participant set change. A CANCELLED Tournament is never touched again (it is not resurrected).
 */
@Component
class BanParticipantReconciler implements TournamentParticipantCauses {

    private final Tournaments tournaments;
    private final TournamentParticipants participants;
    private final TournamentBoundaries boundaries;
    private final ActiveBans bans;

    BanParticipantReconciler(Tournaments tournaments, TournamentParticipants participants,
                             TournamentBoundaries boundaries, ActiveBans bans) {
        this.tournaments = tournaments;
        this.participants = participants;
        this.boundaries = boundaries;
        this.bans = bans;
    }

    @Override
    public void reconcileBans(CommandContext command, UUID userId) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(userId, "userId");
        Instant now = command.commandNow();
        boolean banned = bans.bannedAt(command, userId, now);
        for (UUID tournamentId : participants.liveTournamentsOf(command, userId)) {
            boundaries.settle(command, tournamentId);
            Optional<Tournaments.Current> found = tournaments.current(command, tournamentId).filter(t -> t.live());
            if (found.isEmpty()) {
                continue;                                                    // ended at a boundary just now
            }
            Tournaments.Current tournament = found.get();
            TournamentParticipants.Row row = participants.row(command, tournamentId, userId).orElseThrow();
            if (banned && row.status() == TournamentParticipantStatus.JOINED) {
                participants.markIneligible(command, tournament, row);
            } else if (!banned && row.banOnly() && restorable(tournament, now)) {
                participants.restore(command, tournament, row, now);
            }
        }
    }

    /** FR-TRN-26: only before Start, while the Tournament can still be played as entered. */
    private static boolean restorable(Tournaments.Current tournament, Instant now) {
        return tournament.status() != TournamentStatus.ACTIVE && now.isBefore(tournament.startAt());
    }
}
