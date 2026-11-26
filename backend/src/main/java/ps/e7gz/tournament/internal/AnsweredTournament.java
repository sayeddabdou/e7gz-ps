package ps.e7gz.tournament.internal;

import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.codes.TournamentTerminalReason;

/**
 * A Player-hosted request after the Venue's answer (E7-92): {@code WAITING_FOR_PLAYERS} once approved, {@code REJECTED}
 * with {@code VENUE_REJECTED_TOURNAMENT} once rejected (FR-TRN-10).
 *
 * @param terminalReason {@code null} when approved
 */
public record AnsweredTournament(UUID id, TournamentStatus status, TournamentTerminalReason terminalReason) {

    public AnsweredTournament {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
    }
}
