package ps.e7gz.tournament.internal;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.common.codes.TournamentTerminalReason;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.messaging.api.Outbox;
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
    private final Outbox outbox;

    AccountDisableTournamentCascade(Tournaments tournaments, Outbox outbox) {
        this.tournaments = tournaments;
        this.outbox = outbox;
    }

    @Override
    public List<AwaitingApproval> awaitingApproval(CommandContext command, UUID venueId) {
        List<UUID> ids = command.jdbc().sql(AWAITING_APPROVAL)
                .param("venueId", Objects.requireNonNull(venueId, "venueId"))
                .query(UUID.class).list();
        return ids.stream().map(id -> new AwaitingApproval(id, command.jdbc().sql(Tournaments.LIVE_RESOURCES)
                .param("id", id).query(UUID.class).list())).toList();
    }

    /**
     * E7-92: each creator is told in the same transaction ({@code TournamentCancelled} with
     * {@code VENUE_ACCOUNT_DISABLED}), so E7-89's notification can say the request ended without blaming the Venue's
     * silence (FR-TRN-18).
     */
    @Override
    public void cancel(CommandContext command, List<UUID> tournamentIds) {
        TournamentTerminalReason reason = TournamentTerminalReason.VENUE_ACCOUNT_DISABLED;
        for (UUID id : List.copyOf(tournamentIds)) {
            Tournaments.Current request = tournaments.current(command, id)
                    .orElseThrow(() -> new IllegalArgumentException("No Tournament " + id));
            int sequence = tournaments.cancelled(command, id, reason, AuditActor.system(), command.commandNow())
                    .orElseThrow(() -> new IllegalStateException(
                            "Tournament " + id + " was terminal under the disable's locks"));
            outbox.publish(command, TournamentAnswerEvents.cancelled(request, sequence, reason));
        }
    }
}
