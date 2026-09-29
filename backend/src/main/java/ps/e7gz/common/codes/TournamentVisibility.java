package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Tournament visibility - {@code ck_tournament_visibility}. */
@Schema(enumAsRef = true)
public enum TournamentVisibility {
    PUBLIC,
    PRIVATE
}
