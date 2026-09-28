package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Cause of an INELIGIBLE participant (ADR-023) - {@code ck_tournament_participant_ineligible_reason}. */
@Schema(enumAsRef = true)
public enum TournamentParticipantIneligibleReason {
    BAN,
    ACCOUNT_DELETED
}
