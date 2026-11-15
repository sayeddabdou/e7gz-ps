package ps.e7gz.trust.api;

import java.time.Instant;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: the "banned then" as-of query of E7-69 (arch 07 section 3.4, FR-TRS-10, FR-TRS-19): whether the User was
 * under an operational Ban at a past instant. Answered from {@code trust.ban_instance.activated_at / ends_at} - the
 * operational history - and never from the qualification point: a Ban activates when it is detected, never back-dated
 * (FR-TRS-18), and a later correction can move a qualification without moving the Ban that was actually served.
 *
 * <p>Not an eligibility check. {@link ActiveBans} decides what a banned Customer may do now and counts only an
 * {@code ACTIVE} Ban; this counts a Ban that has since EXPIRED or been LIFTED or CORRECTED, for the time it was in force.
 */
public interface BanHistory {

    /**
     * True when a Ban Instance of the User spanned {@code at}: activated at or before it and ending after it, whatever
     * its status now. A lift or correction ends the Ban at that command's instant
     * ({@code trust.guard_ban_instance} sets {@code ends_at}), and an extension only moves the end later (FR-TRS-22),
     * so the stored interval is the time the Ban was in force. Reads on the command's own transaction.
     */
    boolean bannedAt(CommandContext command, UUID userId, Instant at);
}
