package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Activity — {@code ck_venue_activity}, {@code ck_resource_activity} (FR-VEN-41). */
@Schema(enumAsRef = true)
public enum Activity {
    PLAYSTATION,
    BILLIARDS,
    PING_PONG
}
