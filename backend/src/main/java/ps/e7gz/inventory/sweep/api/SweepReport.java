package ps.e7gz.inventory.sweep.api;

import java.time.Instant;
import java.util.List;

/**
 * Result of one sweep run.
 *
 * @param invariants the codes of every check that ran, in run order
 * @param violations everything found, in check order
 */
public record SweepReport(Instant startedAt, Instant finishedAt, List<String> invariants,
                          List<InvariantViolation> violations) {

    public SweepReport {
        invariants = List.copyOf(invariants);
        violations = List.copyOf(violations);
    }

    public boolean clean() {
        return violations.isEmpty();
    }
}
