package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Outbox event state - {@code ck_outbox_state}. */
@Schema(enumAsRef = true)
public enum OutboxState {
    PENDING,
    DISPATCHED,
    DEAD_LETTER
}
