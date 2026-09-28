package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** PlayStation console — {@code ck_resource_console_type}; PS5 and PS4 only (FR-VEN-41). */
@Schema(enumAsRef = true)
public enum ConsoleType {
    PS4,
    PS5
}
