package ps.e7gz.inventory.availability.api;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The outcome of {@link AvailabilityQuery#isFree}: FREE, or NOT FREE with the first failing {@link AvailabilityCheck}
 * (arch 05 section 2.2). A domain result, not an error: callers decide what a refusal means for their flow.
 */
public final class PredicateResult {

    private static final PredicateResult FREE = new PredicateResult(null);

    /** {@code null} when free. */
    private final AvailabilityCheck failingCheck;

    private PredicateResult(AvailabilityCheck failingCheck) {
        this.failingCheck = failingCheck;
    }

    public static PredicateResult free() {
        return FREE;
    }

    public static PredicateResult notFree(AvailabilityCheck failingCheck) {
        return new PredicateResult(Objects.requireNonNull(failingCheck, "failingCheck"));
    }

    public boolean isFree() {
        return failingCheck == null;
    }

    /** The first failing check; empty when free. */
    public Optional<AvailabilityCheck> failingCheck() {
        return Optional.ofNullable(failingCheck);
    }

    /** The {@link AvailabilityCheck#index()} of the first failing check; empty when free. */
    public OptionalInt failingCheckIndex() {
        return failingCheck == null ? OptionalInt.empty() : OptionalInt.of(failingCheck.index());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PredicateResult that && this.failingCheck == that.failingCheck;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(failingCheck);
    }

    @Override
    public String toString() {
        return failingCheck == null ? "FREE" : "NOT_FREE(" + failingCheck.index() + " " + failingCheck + ")";
    }
}
