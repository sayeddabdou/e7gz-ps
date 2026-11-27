package ps.e7gz.tournament.internal;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;

/**
 * Reads a Tournament's id, Venue and state before the command that acts on it, so that command can name it in its
 * lock set (E7-93; the read-then-lock rule of {@link ps.e7gz.tournament.api.TournamentCascades}). By id, or by a
 * private Tournament's invite code (FR-TRN-06). The acting command reads it again under its locks; this answer only
 * decides NOT_FOUND, the locks and - for a Venue's cancellation - whether the Owner Password gate is asked.
 */
record TournamentLookup(UUID tournamentId, String inviteCode) implements Command<Optional<TournamentLookup.Found>> {

    static final String BY_ID = "SELECT t.id, t.venue_id, t.status FROM tournament.tournament t WHERE t.id = :key";

    static final String BY_INVITE_CODE = """
            SELECT t.id, t.venue_id, t.status FROM tournament.tournament t
             WHERE t.visibility = 'PRIVATE' AND t.invite_code = :key
            """;

    static TournamentLookup byId(UUID tournamentId) {
        return new TournamentLookup(Objects.requireNonNull(tournamentId, "tournamentId"), null);
    }

    static TournamentLookup byInviteCode(String inviteCode) {
        return new TournamentLookup(null, Objects.requireNonNull(inviteCode, "inviteCode"));
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

    @Override
    public Optional<Found> execute(CommandContext context) {
        return context.jdbc().sql(tournamentId != null ? BY_ID : BY_INVITE_CODE)
                .param("key", tournamentId != null ? tournamentId : inviteCode)
                .query((rs, n) -> new Found(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class),
                        TournamentStatus.valueOf(rs.getString("status"))))
                .optional();
    }

    /** What the lookup saw; the acting command decides on what it reads under its locks. */
    record Found(UUID id, UUID venueId, TournamentStatus status) {
    }
}
