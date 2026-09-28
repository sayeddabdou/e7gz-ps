package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** State of an idempotency record - {@code ck_idempotency_state} (state; literal branches). */
@Schema(enumAsRef = true)
public enum IdempotencyState {
    IN_PROGRESS,
    COMPLETED
}
