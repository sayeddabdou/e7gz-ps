package ps.e7gz.tournament.internal;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.TournamentTerminalReason;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.venue.api.AccountDisableTournaments;

/**
 * The Tournament side of a Venue Account disable (arch 06 section 2.8, FR-TRN-18, FR-TRN-19; E7-78): every Tournament
 * still waiting for the venue's approval is cancelled at once under {@code VENUE_ACCOUNT_DISABLED} through the common
 * system cancellation ({@link Tournaments#cancel}): its holds released, its timers superseded, actor SYSTEM. The
 * creator's slot is freed with no 5-minute cooldown and no venue non-response blame - both follow from the reason in
 * the database. Later states continue: an access disable alone does not cancel them.
 */
@Component
class AccountDisableTournamentCascade implements AccountDisableTournaments {

    static final String AWAITING_APPROVAL = """
            SELECT t.id
              FROM tournament.tournament t
             WHERE t.venue_id = :venueId AND t.status = 'PENDING_VENUE_APPROVAL'
             ORDER BY t.start_at, t.id
            """;

    private final Tournaments tournaments;

    AccountDisableTournamentCascade(Tournaments tournaments) {
        this.tournaments = tournaments;
    }

    @Override
    public List<AwaitingApproval> awaitingApproval(CommandContext command, UUID venueId) {
        List<UUID> ids = command.jdbc().sql(AWAITING_APPROVAL)
                .param("venueId", Objects.requireNonNull(venueId, "venueId"))
                .query(UUID.class).list();
        return ids.stream().map(id -> new AwaitingApproval(id, command.jdbc().sql(Tournaments.LIVE_RESOURCES)
                .param("id", id).query(UUID.class).list())).toList();
    }

    @Override
    public void cancel(CommandContext command, List<UUID> tournamentIds) {
        for (UUID id : List.copyOf(tournamentIds)) {
            if (!tournaments.cancel(command, id, TournamentTerminalReason.VENUE_ACCOUNT_DISABLED)) {
                throw new IllegalStateException("Tournament " + id + " was terminal under the disable's locks");
            }
        }
    }
}
