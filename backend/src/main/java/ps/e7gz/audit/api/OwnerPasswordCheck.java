package ps.e7gz.audit.api;

import java.util.Objects;
import ps.e7gz.common.codes.OwnerPasswordOutcome;

/**
 * An Owner Password check that protected the audited action; both outcomes are audited (arch 10 section 8.1). It holds
 * only the outcome - never the password. An action with no Owner Password check simply has none
 * ({@code owner_password_used} and {@code owner_password_outcome} stay NULL).
 */
public record OwnerPasswordCheck(OwnerPasswordOutcome outcome) {

    public OwnerPasswordCheck {
        Objects.requireNonNull(outcome, "outcome");
    }

    public static OwnerPasswordCheck succeeded() {
        return new OwnerPasswordCheck(OwnerPasswordOutcome.SUCCESS);
    }

    public static OwnerPasswordCheck failed() {
        return new OwnerPasswordCheck(OwnerPasswordOutcome.FAILURE);
    }
}
