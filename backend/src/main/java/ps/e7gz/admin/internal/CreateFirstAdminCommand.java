package ps.e7gz.admin.internal;

import java.util.Objects;
import java.util.UUID;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;

/**
 * Inserts the first {@code iam.admin_account} row and its audit row in one transaction on the admin-runtime connection
 * (Sprint 0 review S-5, E7-23). Refused when any admin already exists; the scope's advisory lock makes two concurrent
 * bootstraps run one after the other, so the second one sees the first admin and is refused.
 */
record CreateFirstAdminCommand(String email, String fullName, String passwordHash, UUID correlationId, AuditWriter audit)
        implements Command<UUID> {

    CreateFirstAdminCommand {
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(fullName, "fullName");
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(audit, "audit");
    }

    /** No Venue is in scope, so the category selects no venue rule (ADR-022 names none for account commands). */
    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
    }

    @Override
    public CommandScope scope() {
        return CommandScope.firstAdminBootstrap();
    }

    @Override
    public UUID execute(CommandContext context) {
        boolean anyAdmin = context.jdbc().sql("SELECT EXISTS (SELECT 1 FROM iam.admin_account)")
                .query(Boolean.class).single();
        if (anyAdmin) {
            throw new FirstAdminAlreadyExistsException();
        }
        UUID id = UUID.randomUUID();
        context.jdbc().sql("""
                        INSERT INTO iam.admin_account (id, email, password_hash, full_name, is_active)
                        VALUES (:id, :email, :hash, :fullName, true)""")
                .param("id", id).param("email", email).param("hash", passwordHash).param("fullName", fullName)
                .update();
        audit.write(context, AuditRecord.builder(AdminAuditAction.FIRST_ADMIN_CREATED, AuditActor.system(), id,
                correlationId).build());
        return id;
    }

    @Override
    public String toString() {
        return "CreateFirstAdminCommand[<redacted>]";
    }

    /** A Platform Admin exists already: the bootstrap is one-off. */
    static final class FirstAdminAlreadyExistsException extends RuntimeException {

        FirstAdminAlreadyExistsException() {
            super("A Platform Admin already exists; the first-admin bootstrap runs only on an empty iam.admin_account");
        }
    }
}
