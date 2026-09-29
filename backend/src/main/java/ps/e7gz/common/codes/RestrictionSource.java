package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Who opened a venue account restriction interval - {@code ck_restriction_source}. */
@Schema(enumAsRef = true)
public enum RestrictionSource {
    ADMIN,
    SYSTEM
}
