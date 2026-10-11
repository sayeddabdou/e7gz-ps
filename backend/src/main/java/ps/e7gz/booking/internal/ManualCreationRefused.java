package ps.e7gz.booking.internal;

import java.util.Objects;
import ps.e7gz.common.error.ApiException;

/** A Manual Booking refused by one of the fifteen creation conditions; the API answers with its code (arch 09 §1.5). */
class ManualCreationRefused extends ApiException {

    private final ManualCreationCondition condition;

    ManualCreationRefused(ManualCreationCondition condition) {
        super(condition.code());
        this.condition = Objects.requireNonNull(condition, "condition");
    }

    ManualCreationCondition condition() {
        return condition;
    }
}
