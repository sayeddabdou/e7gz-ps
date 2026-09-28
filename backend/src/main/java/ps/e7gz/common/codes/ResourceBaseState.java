package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Resource base state — {@code ck_resource_base_state} (FR-VEN-13). */
@Schema(enumAsRef = true)
public enum ResourceBaseState {
    ACTIVE,
    DISABLED,
    ARCHIVED
}
