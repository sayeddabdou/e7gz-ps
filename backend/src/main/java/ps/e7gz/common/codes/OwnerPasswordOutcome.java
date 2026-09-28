package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Outcome of an Owner Password check - {@code ck_audit_owner_password_outcome}. */
@Schema(enumAsRef = true)
public enum OwnerPasswordOutcome {
    SUCCESS,
    FAILURE
}
