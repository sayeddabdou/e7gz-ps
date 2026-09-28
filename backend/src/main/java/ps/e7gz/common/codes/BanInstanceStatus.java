package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Ban instance status - {@code ck_ban_instance_status}. */
@Schema(enumAsRef = true)
public enum BanInstanceStatus {
    ACTIVE,
    EXPIRED,
    LIFTED,
    CORRECTED
}
