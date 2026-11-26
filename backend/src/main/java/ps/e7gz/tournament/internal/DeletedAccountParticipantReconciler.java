package ps.e7gz.tournament.internal;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.TournamentParticipantStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.identity.api.AccountDeletionTournaments;

/**
 * O19 precedence over O14 (E7-94; arch 06 section 3.7, arch 07 section 5.7, ADR-023): Identity's
 * {@link AccountDeletionTournaments}. Once the User is anonymised, each of their participations in a non-terminal
 * Tournament is {@code INELIGIBLE/ACCOUNT_DELETED}: a JOINED row through the ordinary INELIGIBLE transition (the guard
 * records ACCOUNT_DELETED because the account is gone), a BAN-only row by the one cause-only reclassification the guard
 * allows. Rows already LEFT, REMOVED or ACCOUNT_DELETED stay as they are, and a terminal Tournament is never touched.
 *
 * <p>Due boundaries are settled first ({@link TournamentBoundaries#settle}): the participant set may not change across
 * a due checkpoint or start ({@code tournament.guard_tournament_participant}).
 *
 * <p>"Never downgrades back" is kept by {@link BanParticipantReconciler} as much as by the database: it moves only
 * JOINED rows to INELIGIBLE and restores only BAN-only rows, so an ACCOUNT_DELETED row is out of its reach.
 */
@Component
class DeletedAccountParticipantReconciler implements AccountDeletionTournaments {

    private final Tournaments tournaments;
    private final TournamentParticipants participants;
    private final TournamentBoundaries boundaries;

    DeletedAccountParticipantReconciler(Tournaments tournaments, TournamentParticipants participants,
                                        TournamentBoundaries boundaries) {
        this.tournaments = tournaments;
        this.participants = participants;
        this.boundaries = boundaries;
    }

    @Override
    public void accountDeleted(CommandContext command, UUID userId) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(userId, "userId");
        for (UUID tournamentId : participants.liveTournamentsOf(command, userId)) {
            boundaries.settle(command, tournamentId);
            Optional<Tournaments.Current> found = tournaments.current(command, tournamentId)
                    .filter(Tournaments.Current::live);
            if (found.isEmpty()) {
                continue;                                                    // ended at a boundary just now
            }
            Tournaments.Current tournament = found.get();
            TournamentParticipants.Row row = participants.row(command, tournamentId, userId).orElseThrow();
            if (row.status() == TournamentParticipantStatus.JOINED) {
                participants.markIneligible(command, tournament, row);
            } else if (row.banOnly()) {
                participants.reclassifyDeleted(command, tournament, row);
            }
        }
    }
}
