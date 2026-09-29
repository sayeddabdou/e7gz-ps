package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Why a resource was removed from a tournament - {@code ck_tournament_resource_removal_shape} (removal_cause). */
@Schema(enumAsRef = true)
public enum TournamentResourceRemovalCause {
    RESOURCE_EMERGENCY_OUTAGE,
    RESOURCE_OWNER_REVIEW_REQUIRED
}
