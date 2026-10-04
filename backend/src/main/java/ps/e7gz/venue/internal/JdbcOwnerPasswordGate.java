package ps.e7gz.venue.internal;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
import ps.e7gz.common.security.owner.OwnerProtected;
import ps.e7gz.identity.api.OwnerPasswordAttempts;
import ps.e7gz.venue.api.OwnerPasswordGate;

/**
 * {@link OwnerPasswordGate} on {@code iam.venue_owner_secret} (arch 10 §3). The check runs inside the protected
 * command's transaction, immediately before its change. A refusal rolls that transaction back and is then recorded in
 * its own transaction - the audit row (FR-VEN-05) and, for a wrong password, the attempt counter (NFR-16) - so the
 * record persists while the action changes nothing (arch 10 §3.1: "the audit persists").
 */
@Component
class JdbcOwnerPasswordGate implements OwnerPasswordGate {

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
        return OwnerPasswordCheck.succeeded();
    }

    @Override
    public <R> R run(Command<R> protectedCommand, UUID venueAccountId, UUID correlationId) {
        OwnerProtected protection = protectedCommand.getClass().getAnnotation(OwnerProtected.class);
        if (protection == null) {
            throw new IllegalArgumentException(
                    protectedCommand.getClass().getName() + " is not annotated @OwnerProtected (arch 10 §3.3)");
        }
        Objects.requireNonNull(venueAccountId, "venueAccountId");
        Objects.requireNonNull(correlationId, "correlationId");
        try {
            return runner.run(protectedCommand);
        } catch (OwnerPasswordRefused refused) {
            if (!refused.venueAccountId().equals(venueAccountId)) {
                throw new IllegalStateException("Owner Password checked for another venue account than the actor's");
            }
            runner.run(new RecordRefusal(venueAccountId, correlationId, protection.value(), refused.lockedOut(),
                    attempts, audit));
            throw new ApiException(refused.lockedOut() ? ApiErrorCode.RATE_LIMITED : ApiErrorCode.OWNER_PASSWORD_INVALID);
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
}
