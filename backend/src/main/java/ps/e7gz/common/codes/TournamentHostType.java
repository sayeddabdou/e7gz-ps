package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Who hosts a tournament - {@code ck_tournament_host_type}. */
@Schema(enumAsRef = true)
public enum TournamentHostType {
    PLAYER,
    VENUE
}
