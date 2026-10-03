package ps.e7gz.venue.api;

import java.util.UUID;
import ps.e7gz.audit.api.OwnerPasswordCheck;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;

/**
 * The Owner Password for protected venue actions (FRS §2.4, ADR-011, arch 10 §3). Used together:
 * <ol>
 *   <li>the protected command carries {@code @OwnerProtected} and, first thing in {@code execute}, calls
 *       {@link #verify} with the password from the request body;</li>
 *   <li>its caller runs it with {@link #run}, never with {@code CommandRunner} directly.</li>
 * </ol>
 * A right password lets the command go on, and its own audit row records the {@link OwnerPasswordCheck}. A wrong one
 * rolls the command back; {@link #run} then records the failure in a separate transaction (audit row and attempt
 * counter, arch 10 §3.1) and answers {@code 423 OWNER_PASSWORD_INVALID}. While the account is locked out (5 wrong
 * passwords in 15 minutes) every protected action answers {@code 429 RATE_LIMITED} without checking the password.
 *
 * <p>Nothing is ever cached or unlocked: two protected actions in a row each check the password (FR-VEN-02).
 */
public interface OwnerPasswordGate {

    /**
     * Checks the supplied Owner Password of the venue account against {@code iam.venue_owner_secret}, inside the
     * running command (FR-VEN-02, arch 10 §3.4). Returns only on a match; otherwise it aborts the command, which
     * {@link #run} then reports. A change committed before this read is the one checked (FR-VEN-46).
     */
    OwnerPasswordCheck verify(CommandContext command, UUID venueAccountId, String suppliedPassword);

    /**
     * Runs a command annotated {@code @OwnerProtected} for the venue account. A refused Owner Password is audited
     * under the annotation's action id with the venue as actor, then answered as described above.
     *
     * @throws IllegalArgumentException when the command's class is not annotated {@code @OwnerProtected}
     */
    <R> R run(Command<R> protectedCommand, UUID venueAccountId, UUID correlationId);
}
