package ps.e7gz.tournament.internal;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;

/**
 * Reads the Tournament's Venue before the command that answers it, so that command can name it in its lock set (E7-92;
 * the read-then-lock rule of {@link ps.e7gz.tournament.api.TournamentCascades}). The answering command reads it again
 * under its locks; this answer only decides NOT_FOUND and the locks.
 */
record FindTournament(UUID tournamentId) implements Command<Optional<UUID>> {

    static final String VENUE_OF = "SELECT t.venue_id FROM tournament.tournament t WHERE t.id = :id";

    FindTournament {
        Objects.requireNonNull(tournamentId, "tournamentId");
    }

    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
    }

    /** Locks nothing: reads, then the command commits. */
    @Override
    public CommandScope scope() {
        return CommandScope.shared(LockSet.empty());
    }

    /** The Tournament's Venue, or empty when there is no such Tournament. */
    @Override
    public Optional<UUID> execute(CommandContext context) {
        return context.jdbc().sql(VENUE_OF).param("id", tournamentId).query(UUID.class).optional();
    }
}
