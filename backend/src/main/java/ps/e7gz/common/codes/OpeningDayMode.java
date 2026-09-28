package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** How a weekday uses the opening-hours template — {@code ck_opening_day_mode} (FR-VEN-17). */
@Schema(enumAsRef = true)
public enum OpeningDayMode {
    DEFAULT,
    CUSTOM,
    CLOSED
}
