package ps.e7gz.inventory.availability.api;

import java.time.Instant;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * The one canonical availability predicate (arch 01 section 15 rule 1, {@code FR-AVL-01}, {@code FR-AVL-02}). Booking,
 * Late Confirm, Tournament and Operations flows ask this interface; none of them re-implements, reorders or caches
 * around the eight checks (enforced by {@code AvailabilityPredicateSingularityTest}).
 *
 * <p>A live eligibility query, not a guarantee: when a commitment is written, the database exclusion constraint and
 * cross-class guards stay the authority (arch 01 section 15 rule 2).
 */
public interface AvailabilityQuery {

    /**
     * Evaluates the eight checks of {@link AvailabilityCheck} in their frozen order and returns at the first failing
     * one (arch 05 section 2.2).
     *
     * <p>Every read runs on {@code command}'s own transaction ({@link CommandContext#jdbc()}), so a commit-time
     * revalidation sees the command's own writes (PO decision 3 Oct 2026). No transaction is opened here.
     *
     * @param command the caller's command; its transaction carries every read
     * @param period  the interval {@code [start, end)} asked about (checks 5-8)
     * @param at      the instant the selling-block states are read at (checks 1-2), normally
     *                {@link CommandContext#commandNow()}
     * @throws ps.e7gz.common.error.ApiException {@code NOT_FOUND} when the Resource does not exist
     */
    PredicateResult isFree(CommandContext command, UUID resourceId, AvailabilityPeriod period, Instant at);
}
