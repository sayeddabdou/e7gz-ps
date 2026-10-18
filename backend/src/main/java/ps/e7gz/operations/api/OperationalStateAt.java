package ps.e7gz.operations.api;

import java.time.Instant;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: the operational causes of a start-time cancellation as they stood at a past instant (Spec v5 sections
 * 31.3 and 31.6, FR-BOK-27; E7-59 delayed-start race, for E7-60's start-time resolution). A late worker or a delayed
 * start must decide from the state at {@code startAt}, never at the time it runs (arch 08 section 2.2) - the same
 * interval semantics as {@code tournament.highest_current_cancellation_reason}: in force at {@code at} means entered or
 * declared at or before {@code at} and not yet resolved or ended at {@code at}. Every read runs on the command's own
 * transaction.
 */
public interface OperationalStateAt {

    /** An Owner Review of the Resource in force at {@code at} ({@code RESOURCE_OWNER_REVIEW_REQUIRED}). */
    boolean ownerReviewAt(CommandContext command, UUID resourceId, Instant at);

    /** A {@code RESOURCE}-scoped Emergency of the Resource unresolved at {@code at} ({@code RESOURCE_EMERGENCY_OUTAGE}). */
    boolean resourceEmergencyAt(CommandContext command, UUID resourceId, Instant at);

    /** A {@code VENUE}-scoped Emergency of the Venue unresolved at {@code at} ({@code VENUE_EMERGENCY_CLOSURE}). */
    boolean venueEmergencyAt(CommandContext command, UUID venueId, Instant at);

    /**
     * A Venue Closure of the Venue overlapping the booked {@code [start, end)} - one that "still prevents service"
     * ({@code VENUE_CLOSURE}, section 31.3). Named as the cause it is (E7-47 D3: causes are read explicitly, never
     * inferred from a failing availability check); a Closure has no creation instant, so it counts whenever it overlaps.
     */
    boolean closurePreventsService(CommandContext command, UUID venueId, Instant start, Instant end);
}
