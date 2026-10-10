package ps.e7gz.operations.api;

import java.time.Instant;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: whether a Venue or a Resource is under an unresolved Emergency now - the operational causes of FR-BOK-16
 * and FR-OPS-34/35, named explicitly rather than inferred from a failing availability check (E7-47, PO decision D3
 * 10 Oct 2026). Not an availability answer: the selling block an Emergency puts in force is still check 1-2 of the
 * canonical predicate. Every read runs on the command's own transaction.
 */
public interface UnresolvedEmergencies {

    /**
     * A {@code VENUE}-scoped Emergency of the Venue, declared at or before {@code at} and still unresolved
     * ({@code ACTIVE} or {@code AWAITING_CONFIRMATION}): a venue emergency ({@code VENUE_EMERGENCY_CLOSURE}).
     */
    boolean venueEmergency(CommandContext command, UUID venueId, Instant at);

    /**
     * A {@code RESOURCE}-scoped Emergency of the Resource, declared at or before {@code at} and still unresolved: a
     * resource emergency ({@code RESOURCE_EMERGENCY_OUTAGE}).
     */
    boolean resourceEmergency(CommandContext command, UUID resourceId, Instant at);
}
