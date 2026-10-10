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
}
