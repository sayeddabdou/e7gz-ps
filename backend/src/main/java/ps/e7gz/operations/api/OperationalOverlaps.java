package ps.e7gz.operations.api;

import java.time.Instant;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: whether an Emergency, Maintenance or Venue Closure overlaps {@code [start, end)}, for checks 5-7 of the
 * availability predicate (arch 05 section 2.2; PO decision 3 Oct 2026). Intervals are half-open, so back-to-back
 * intervals do not overlap. Every read runs on the command's own transaction; {@code end} must be after {@code start}.
 */
public interface OperationalOverlaps {

    /**
     * Check 5: an unresolved ({@code ACTIVE} / {@code AWAITING_CONFIRMATION}) Emergency whose declared interval
     * {@code [declared_at, expected_end_at)} overlaps {@code [start, end)}, and which is either {@code VENUE}-scoped on
     * {@code venueId} or {@code RESOURCE}-scoped on {@code resourceId}. Only the declared interval counts: the
     * open-ended part of an unresolved Emergency is its selling block (checks 1-2), never an infinite interval
     * ({@code FR-AVL-02}). A resolved ({@code ENDED} / {@code CONVERTED}) Emergency does not block here.
     */
    boolean declaredEmergencyOverlaps(CommandContext command, UUID venueId, UUID resourceId, Instant start,
                                      Instant end);

    /** Check 6: a Maintenance window of the Resource overlaps {@code [start, end)}. */
    boolean maintenanceOverlaps(CommandContext command, UUID resourceId, Instant start, Instant end);

    /** Check 7: a Closure of the Venue overlaps {@code [start, end)}. */
    boolean closureOverlaps(CommandContext command, UUID venueId, Instant start, Instant end);
}
