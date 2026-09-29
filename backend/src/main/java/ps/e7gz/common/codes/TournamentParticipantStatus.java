package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Tournament participant status - {@code ck_tournament_participant_status}. */
@Schema(enumAsRef = true)
public enum TournamentParticipantStatus {
    JOINED,
    LEFT,
    REMOVED,
    INELIGIBLE
}
