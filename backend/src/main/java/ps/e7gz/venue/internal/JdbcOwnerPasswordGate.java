package ps.e7gz.venue.internal;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.audit.api.OwnerPasswordCheck;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.error.DatabaseErrorCodes;
import ps.e7gz.common.security.owner.OwnerProtected;
import ps.e7gz.identity.api.OwnerPasswordAttempts;
import ps.e7gz.venue.api.OwnerPasswordGate;

/**
 * {@link OwnerPasswordGate} on {@code iam.venue_owner_secret} (arch 10 §3). The check runs inside the protected
 * command's transaction, immediately before its change. A refusal rolls that transaction back and is then recorded in
 * its own transaction - the audit row (FR-VEN-05) and, for a wrong password, the attempt counter (NFR-16) - so the
 * record persists while the action changes nothing (arch 10 §3.1: "the audit persists"). So is a right password whose
 * action was then refused: one OWNER_PASSWORD_ACCEPTED row, not counted (PO decision Q2, 24 Oct 2026).
 *
 * <p>Which attempt checked the password is tracked per run: the command {@code CommandRunner} is given is always this
 * gate's {@link Tracked}, which resets its {@link Attempt} in {@link Command#beforeAttempt} - before every attempt's
 * transaction, the reruns after {@code 40001} included - and binds it to the thread only while the command executes,
 * where {@link #verify} marks it. A refusal is taken for one after a right password only when the attempt it ended
 * checked the password; a refusal at commit ends the attempt whose {@code execute} has just returned.
 */
@Component
class JdbcOwnerPasswordGate implements OwnerPasswordGate {

    /**
     * The attempt of the protected command executing on this thread, set only during its {@code execute}.
     * {@code CommandRunner} runs a command on the caller's thread and never inside another transaction, so a thread
     * executes at most one protected command at a time.
     */
    private static final ThreadLocal<Attempt> EXECUTING = new ThreadLocal<>();

    private final CommandRunner runner;
    private final PasswordEncoder passwords;
    private final OwnerPasswordAttempts attempts;
    private final AuditWriter audit;

    JdbcOwnerPasswordGate(CommandRunner runner, PasswordEncoder passwords, OwnerPasswordAttempts attempts,
                          AuditWriter audit) {
        this.runner = runner;
        this.passwords = passwords;
        this.attempts = attempts;
        this.audit = audit;
    }

    @Override
    public OwnerPasswordCheck verify(CommandContext command, UUID venueAccountId, String suppliedPassword) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(venueAccountId, "venueAccountId");
        // No value sent is not a guess: 423 OWNER_PASSWORD_REQUIRED, not counted, nothing written (arch 09 §1.5;
        // PO decision 4 Oct 2026).
        if (suppliedPassword == null || suppliedPassword.isBlank()) {
            throw new ApiException(ApiErrorCode.OWNER_PASSWORD_REQUIRED);
        }
        if (attempts.lockedOut(command, venueAccountId)) {
            throw new OwnerPasswordRefused(venueAccountId, true);
        }
        // FOR SHARE: a change of the secret still in flight finishes first, so the value checked is the current one.
        String hash = command.jdbc().sql("""
                        SELECT owner_password_hash FROM iam.venue_owner_secret
                         WHERE venue_account_id = :account FOR SHARE""")
                .param("account", venueAccountId).query(String.class).optional().orElse(null);
        if (hash == null || !passwords.matches(suppliedPassword, hash)) {
            throw new OwnerPasswordRefused(venueAccountId, false);
        }
        Attempt executing = EXECUTING.get();
        if (executing != null && executing.isOf(command, venueAccountId)) {
            executing.accepted = true;
        }
        return OwnerPasswordCheck.succeeded();
    }

    @Override
    public OwnerPasswordCheck verified(CommandContext command, UUID venueAccountId) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(venueAccountId, "venueAccountId");
        Attempt executing = EXECUTING.get();
        if (executing == null || !executing.isOf(command, venueAccountId) || !executing.accepted) {
            throw new IllegalStateException("The gate has not checked this venue account's Owner Password in this "
                    + "attempt (arch 10 §3.3)");
        }
        return OwnerPasswordCheck.succeeded();
    }

    @Override
    public <R> R run(Command<R> protectedCommand, UUID venueAccountId, UUID correlationId) {
        return guarded(protectedCommand, venueAccountId, correlationId, false, null);
    }

    @Override
    public <R> R run(Command<R> protectedCommand, UUID venueAccountId, String suppliedPassword, UUID correlationId) {
        return guarded(protectedCommand, venueAccountId, correlationId, true, suppliedPassword);
    }

    private <R> R guarded(Command<R> protectedCommand, UUID venueAccountId, UUID correlationId, boolean verifyFirst,
                          String suppliedPassword) {
        OwnerProtected protection = protectedCommand.getClass().getAnnotation(OwnerProtected.class);
        if (protection == null) {
            throw new IllegalArgumentException(
                    protectedCommand.getClass().getName() + " is not annotated @OwnerProtected (arch 10 §3.3)");
        }
        Objects.requireNonNull(venueAccountId, "venueAccountId");
        Objects.requireNonNull(correlationId, "correlationId");
        if (EXECUTING.get() != null) {
            throw new IllegalStateException("A protected command cannot run inside another protected command");
        }
        Attempt attempt = new Attempt(venueAccountId);
        try {
            return runner.run(new Tracked<>(protectedCommand, attempt, verifyFirst, suppliedPassword, this));
        } catch (OwnerPasswordRefused refused) {
            if (!refused.venueAccountId().equals(venueAccountId)) {
                throw new IllegalStateException("Owner Password checked for another venue account than the actor's");
            }
            runner.run(new RecordRefusal(venueAccountId, correlationId, protection.value(), refused.lockedOut(),
                    attempts, audit));
            throw new ApiException(refused.lockedOut() ? ApiErrorCode.RATE_LIMITED : ApiErrorCode.OWNER_PASSWORD_INVALID);
        } catch (RuntimeException failure) {
            // Q2: the last attempt checked the password and was then refused. Only what the API answers as a refusal
            // is audited, with the code it answers: an ApiException, or a database guard with a code (a
            // DataAccessException, as GlobalExceptionHandler maps it), the one that fails the COMMIT included
            // (CommandRunner, PO decision 25 Oct 2026). A 40001 left after the retries and unexpected failures - a
            // guard or invariant without a code, at COMMIT too, answered 500 - are not.
            if (attempt.accepted) {
                Optional<ApiErrorCode> refusal = failure instanceof ApiException api ? Optional.of(api.code())
                        : failure instanceof DataAccessException database ? DatabaseErrorCodes.translate(database)
                        : Optional.empty();
                if (refusal.isPresent()) {
                    runner.run(new RecordAcceptance(venueAccountId, correlationId, protection.value(), refusal.get(),
                            audit));
                }
            }
            throw failure;
        }
    }

    /**
     * One run's state: whether its current attempt checked the venue account's password, and that attempt's context.
     * Reset before every attempt; read by {@link #guarded} once the runner has given up.
     */
    private static final class Attempt {

        private final UUID venueAccountId;
        private CommandContext context;
        private boolean accepted;

        Attempt(UUID venueAccountId) {
            this.venueAccountId = venueAccountId;
        }

        void reset() {
            context = null;
            accepted = false;
        }

        /** The same attempt (the very context the runner passed it) and the same venue account. */
        boolean isOf(CommandContext command, UUID account) {
            return command == context && venueAccountId.equals(account);
        }
    }

    /**
     * The command the gate gives {@code CommandRunner}: the protected command, with the run's {@link Attempt} reset
     * before every attempt and bound to this thread while it executes - and, for {@link #run(Command, UUID, String,
     * UUID)}, the password checked first, before anything the command does. The gate builds it itself, so it is always
     * the outermost command and no wrapper inside can skip the reset. Same category and lock set; no transaction,
     * database access or audit of its own. Never prints the password.
     */
    private static final class Tracked<R> implements Command<R> {

        private final Command<R> command;
        private final Attempt attempt;
        private final boolean verifyFirst;
        private final String suppliedPassword;
        private final JdbcOwnerPasswordGate gate;

        Tracked(Command<R> command, Attempt attempt, boolean verifyFirst, String suppliedPassword,
                JdbcOwnerPasswordGate gate) {
            this.command = command;
            this.attempt = attempt;
            this.verifyFirst = verifyFirst;
            this.suppliedPassword = suppliedPassword;
            this.gate = gate;
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return command.eligibilityCategory();
        }

        @Override
        public CommandScope scope() {
            return command.scope();
        }

        @Override
        public void beforeAttempt(int attemptNumber) {
            attempt.reset();
            command.beforeAttempt(attemptNumber);
        }

        @Override
        public R execute(CommandContext context) {
            attempt.context = context;
            EXECUTING.set(attempt);
            try {
                if (verifyFirst) {
                    gate.verify(context, attempt.venueAccountId, suppliedPassword);
                }
                return command.execute(context);
            } finally {
                EXECUTING.remove();
            }
        }

        @Override
        public String toString() {
            return "Tracked[" + command + "]";
        }
    }

    /**
     * The refused attempt, committed on its own: an audit row with owner_password_outcome = FAILURE, the venue as
     * actor and the protected action's id; a wrong password also counts towards the lockout. An attempt refused because
     * the account was already locked out is audited but not counted again.
     */
    record RecordRefusal(UUID venueAccountId, UUID correlationId, String protectedAction, boolean lockedOut,
                         OwnerPasswordAttempts attempts, AuditWriter audit) implements Command<Void> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        /** The counter row is the lock: the upsert takes it. */
        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public Void execute(CommandContext context) {
            if (!lockedOut) {
                attempts.recordFailure(context, venueAccountId);
            }
            audit.write(context, AuditRecord.builder(VenueAccountAuditAction.OWNER_PASSWORD_REFUSED,
                            AuditActor.venue(venueAccountId), venueAccountId, correlationId)
                    .ownerPasswordCheck(OwnerPasswordCheck.failed())
                    .payload(Map.of("protectedAction", protectedAction, "lockedOut", lockedOut))
                    .build());
            return null;
        }
    }

    /**
     * A right password whose action was then refused, committed on its own (PO decision Q2, 24 Oct 2026): an audit row
     * with owner_password_outcome = SUCCESS, the venue as actor, the protected action's id and the refusal's code. Not
     * an attempt: the counter is not touched.
     */
    record RecordAcceptance(UUID venueAccountId, UUID correlationId, String protectedAction, ApiErrorCode refusal,
                            AuditWriter audit) implements Command<Void> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public Void execute(CommandContext context) {
            audit.write(context, AuditRecord.builder(VenueAccountAuditAction.OWNER_PASSWORD_ACCEPTED,
                            AuditActor.venue(venueAccountId), venueAccountId, correlationId)
                    .ownerPasswordCheck(OwnerPasswordCheck.succeeded())
                    .payload(Map.of("protectedAction", protectedAction, "refusalCode", refusal.name()))
                    .build());
            return null;
        }
    }
}
