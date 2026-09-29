package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Who performed an action - {@code ck_audit_actor_type}, {@code ck_booking_history_actor_type_values}, {@code
 * ck_resource_state_change_actor_type}, {@code ck_tournament_history_actor_type}, {@code ck_idempotency_values}
 * (principal_type).
 */
@Schema(enumAsRef = true)
public enum ActorType {
    CUSTOMER,
    VENUE,
    ADMIN,
    SYSTEM
}
