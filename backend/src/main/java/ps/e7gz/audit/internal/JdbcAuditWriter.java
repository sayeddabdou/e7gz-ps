package ps.e7gz.audit.internal;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.common.command.CommandContext;

/**
 * INSERT-only writer on the command's own transaction: {@link CommandContext#jdbc()} for the statement and
 * {@link CommandContext#commandNow()} for {@code occurred_at}. It opens no transaction or connection of its own, so the
 * row commits or rolls back with the command. The database checks actor provenance, secret payload keys, blank reasons
 * and the Owner Password coherence ({@code audit.guard_audit_insert}, {@code ck_audit_*}); their errors propagate
 * unchanged.
 */
@Component
class JdbcAuditWriter implements AuditWriter {

    static final String INSERT = """
            INSERT INTO audit.audit_event (id, occurred_at, actor_type, actor_id, action, subject_type, subject_id,
                                           reason, owner_password_used, owner_password_outcome, correlation_id, payload)
            VALUES (:id, :occurredAt, :actorType, :actorId, :action, :subjectType, :subjectId,
                    :reason, :ownerPasswordUsed, :ownerPasswordOutcome, :correlationId, CAST(:payload AS jsonb))
            """;

    @Override
    public UUID write(CommandContext command, AuditRecord record) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(record, "record");
        // The event id is the audit row's own identity; the correlation id always comes from the record.
        AuditEventRow row = AuditEventRow.of(UUID.randomUUID(), command.commandNow(), record);
        command.jdbc().sql(INSERT)
                .param("id", row.id())
                .param("occurredAt", OffsetDateTime.ofInstant(row.occurredAt(), ZoneOffset.UTC))
                .param("actorType", row.actorType())
                .param("actorId", row.actorId())
                .param("action", row.action())
                .param("subjectType", row.subjectType())
                .param("subjectId", row.subjectId())
                .param("reason", row.reason())
                .param("ownerPasswordUsed", row.ownerPasswordUsed())
                .param("ownerPasswordOutcome", row.ownerPasswordOutcome())
                .param("correlationId", row.correlationId())
                .param("payload", row.payloadJson())
                .update();
        return row.id();
    }
}
