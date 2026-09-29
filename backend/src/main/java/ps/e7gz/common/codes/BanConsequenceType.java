package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** What consuming a ban qualification did - {@code ck_ban_qualification_consumption_type}. */
@Schema(enumAsRef = true)
public enum BanConsequenceType {
    CREATE,
    EXTEND
}
