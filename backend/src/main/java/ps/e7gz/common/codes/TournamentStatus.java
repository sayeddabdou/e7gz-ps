package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Tournament status - {@code ck_tournament_status_values}, {@code ck_tournament_history_status_values}
 * (from_status, to_status).
 */
@Schema(enumAsRef = true)
public enum TournamentStatus {
    PENDING_VENUE_APPROVAL,
    WAITING_FOR_PLAYERS,
    CONFIRMED,
    ACTIVE,
    COMPLETED,
    REJECTED,
    CANCELLED
}
