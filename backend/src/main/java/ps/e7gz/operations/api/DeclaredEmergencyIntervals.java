package ps.e7gz.operations.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: the declared Emergency intervals that check 5 of the availability predicate counts against a Resource,
 * for the candidate boundaries of Nearest Availability (arch 05 section 5.2, E7-33; PO decision 7 Oct 2026). It only
 * tells the search where to look next; it is not an availability answer, and every candidate is still asked of
 * {@code AvailabilityQuery.isFree}.
 */
public interface DeclaredEmergencyIntervals {

    /**
     * The declared intervals {@code [declared_at, expected_end_at)} of the Emergencies {@link
     * OperationalOverlaps#declaredEmergencyOverlaps} would find for {@code venueId} / {@code resourceId} - unresolved,
     * and {@code VENUE}-scoped on the Venue or {@code RESOURCE}-scoped on the Resource - that overlap
     * {@code [start, end)}, merged and in order. Reads on the command's own transaction; {@code end} must be after
     * {@code start}.
     */
    List<Interval> blocking(CommandContext command, UUID venueId, UUID resourceId, Instant start, Instant end);

    /** One merged declared interval {@code [start, end)}. */
    record Interval(Instant start, Instant end) {

        public Interval {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
        }
    }
}
