package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** PlayStation play mode — a pricing mode, never a player count; {@code ck_default_price_play_mode} (FR-VEN-42). */
@Schema(enumAsRef = true)
public enum PlayMode {
    SINGLE,
    MULTIPLAYER
}
