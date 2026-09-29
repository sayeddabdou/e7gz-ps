package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Reason of a terminal tournament outcome (ADR-016) - {@code ck_tournament_terminal_reason_values}, {@code
 * ck_tournament_history_terminal_reason_values}.
 */
@Schema(enumAsRef = true)
public enum TournamentTerminalReason {
    CREATOR_CANCELLED,
    VENUE_CANCELLED,
    ADMIN_CANCELLED,
    VENUE_REJECTED_TOURNAMENT,
    VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME,
    MINIMUM_PARTICIPANTS_NOT_REACHED,
    CREATOR_VERIFIED_STATUS_REVOKED,
    TOURNAMENT_OPERATIONAL_TERMINATION,
    EMERGENCY_CLOSURE,
    RESOURCE_EMERGENCY_OUTAGE,
    RESOURCE_OWNER_REVIEW_REQUIRED,
    VENUE_ACCOUNT_DISABLED
}
