package ps.e7gz.trust.api;

import java.time.Instant;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: whether a Customer is under an operational Ban (FR-ACC-17), for every action that creates or strengthens
 * an Online commitment - first online booking creation (E7-46, FR-BOK-11 condition 2; PO decision 9 Oct 2026).
 * {@code begin_command} locks the Customer's Ban rows when the command names the Customer, so the answer cannot change
 * before the command commits; {@code tr_05_booking_ban_eligibility} remains the database's final refusal.
 */
public interface ActiveBans {

    /**
     * True when an {@code ACTIVE} Ban of the User is in force at {@code at}: activated at or before it and ending
     * after it. Reads on the command's own transaction.
     */
    boolean bannedAt(CommandContext command, UUID userId, Instant at);

    /**
     * True when a Ban of the User was in force at {@code at} as its history records it - activated at or before it and
     * ending after it - whatever its status is now: a lifted or corrected Ban ends at the instant it was lifted or
     * corrected, an expired one at its {@code endsAt}. For a past Product boundary, which is decided as of the boundary
     * and never as of the worker's time (arch 06 section 3.7, ADR-023 item 5; E7-93): the same test as
     * {@code tournament.guard_tournament_due_boundary} at the checkpoint.
     */
    boolean effectiveAt(CommandContext command, UUID userId, Instant at);

    /**
     * As {@link #effectiveAt}, with a Ban that ends exactly at {@code at} counted as still in force: at a Tournament's
     * {@code startAt} a Ban expiring or lifted at that very instant does not restore the Participant (Product section
     * 37.11, FR-TRN-26; physical model v1.1.27, {@code tournament.guard_tournament_due_boundary} at the start).
     */
    boolean effectiveThrough(CommandContext command, UUID userId, Instant at);
}
