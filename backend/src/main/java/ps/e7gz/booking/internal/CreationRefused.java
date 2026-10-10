package ps.e7gz.booking.internal;

import java.util.Objects;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;

/** An online booking refused by one of the twenty creation conditions; the API answers with its code (arch 09 §1.5). */
class CreationRefused extends ApiException {

    private final OnlineCreationCondition condition;

    CreationRefused(OnlineCreationCondition condition) {
        this(condition, condition.code());
    }

    /** Condition 1 answers {@code ACCOUNT_DELETED} instead for an anonymised account (FR-ACC-10). */
    CreationRefused(OnlineCreationCondition condition, ApiErrorCode code) {
        super(code);
        this.condition = Objects.requireNonNull(condition, "condition");
    }

    OnlineCreationCondition condition() {
        return condition;
    }
}
