package ps.e7gz.inventory.sweep.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One row the sweep found breaking an invariant. Shaped so a reporter can raise it as one admin alert later (E7-56):
 * {@code subjectType} / {@code subjectId} name the row the alert is about, {@code facts} carry the ids and values
 * that show the violation (never rule text).
 *
 * @param invariant the {@link InvariantCheck#invariant()} code of the check that found it
 */
public record InvariantViolation(String invariant, InvariantSeverity severity, String subjectType, UUID subjectId,
                                 Map<String, String> facts) {

    public InvariantViolation {
        Objects.requireNonNull(invariant, "invariant");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(subjectType, "subjectType");
        Objects.requireNonNull(subjectId, "subjectId");
        facts = Map.copyOf(facts);
    }
}
