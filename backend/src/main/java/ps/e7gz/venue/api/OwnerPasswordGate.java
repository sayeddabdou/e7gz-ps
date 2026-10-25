package ps.e7gz.venue.api;

import java.util.UUID;
import ps.e7gz.audit.api.OwnerPasswordCheck;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;

/**
 * The Owner Password for protected venue actions (FRS §2.4, ADR-011, arch 10 §3). Used in one of two ways:
 * <ol>
 *   <li>the protected command carries {@code @OwnerProtected} and, first thing in {@code execute}, calls
 *       {@link #verify} with the password from the request body; its caller runs it with
 *       {@link #run(Command, UUID, UUID)};</li>
 *   <li>a protected write behind an {@code Idempotency-Key}: its caller runs it with
 *       {@link #run(Command, UUID, String, UUID)}, which checks the password before the command - so before the
 *       idempotency lookup, and a replay is checked like a first request (PO decision Q3, 24 Oct 2026) - and the
 *       protected command calls {@link #verified} instead of {@link #verify}.</li>
 * </ol>
 * Never with {@code CommandRunner} directly. A right password lets the command go on, and its own audit row records the
 * {@link OwnerPasswordCheck}. A wrong one rolls the command back; the gate then records the failure in a separate
 * transaction (audit row and attempt counter, arch 10 §3.1) and answers {@code 423 OWNER_PASSWORD_INVALID}. While the
 * account is locked out (5 wrong passwords in 15 minutes) every protected action answers {@code 429 RATE_LIMITED}
 * without checking the password. A right password whose action is then refused - after the check, in the same attempt,
 * with a code the API answers (an {@code ApiException}, at commit included, or a database guard with a code) - rolls
 * back too; the gate then records one {@code OWNER_PASSWORD_ACCEPTED} audit row with that code in a separate
 * transaction, not counted as an attempt (PO decision Q2, 24 Oct 2026). An unexpected failure (HTTP 500) is not a
 * refusal and gets no such row.
 *
 * <p>Nothing is ever cached or unlocked: two protected actions in a row each check the password (FR-VEN-02).
 */
public interface OwnerPasswordGate {

    /**
     * Checks the supplied Owner Password of the venue account against {@code iam.venue_owner_secret}, inside the
     * running command (FR-VEN-02, arch 10 §3.4). Returns only on a match; otherwise it aborts the command, which the
     * gate then reports. A change committed before this read is the one checked (FR-VEN-46).
     */
    OwnerPasswordCheck verify(CommandContext command, UUID venueAccountId, String suppliedPassword);

    /**
     * The check {@link #run(Command, UUID, String, UUID)} made for the venue account in this attempt of the running
     * command, for the protected command's own audit row. It checks nothing itself.
     *
     * @throws IllegalStateException when the gate has not checked the account's password in this attempt
     */
    OwnerPasswordCheck verified(CommandContext command, UUID venueAccountId);

    /**
     * Runs a command annotated {@code @OwnerProtected} for the venue account; the command checks the password itself
     * with {@link #verify}. A refused Owner Password is audited under the annotation's action id with the venue as
     * actor, then answered as described above.
     *
     * @throws IllegalArgumentException when the command's class is not annotated {@code @OwnerProtected}
     */
    <R> R run(Command<R> protectedCommand, UUID venueAccountId, UUID correlationId);

    /**
     * Runs a command annotated {@code @OwnerProtected} for the venue account after checking the supplied password in
     * the same transaction, first thing after {@code begin_command}'s locks and before anything the command does - the
     * {@code Idempotency-Key} lookup of a command the {@code Idempotency} wrapper wraps included. The command reads the
     * check with {@link #verified}. Refusals are audited and answered as for {@link #run(Command, UUID, UUID)}.
     *
     * @throws IllegalArgumentException when the command's class is not annotated {@code @OwnerProtected}
     */
    <R> R run(Command<R> protectedCommand, UUID venueAccountId, String suppliedPassword, UUID correlationId);
}
