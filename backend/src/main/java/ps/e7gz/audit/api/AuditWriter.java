package ps.e7gz.audit.api;

import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * Appends one {@code audit.audit_event} row, inside the running command (FR-AUD-03, FR-AUD-06, arch 10 section 8).
 *
 * <p>The row is written on the command's own transaction ({@link CommandContext#jdbc()}), with
 * {@code occurred_at} = {@link CommandContext#commandNow()}: it commits or rolls back together with the command. The
 * audit trail is append-only, so this interface has no update or delete.
 */
public interface AuditWriter {

    /** Writes the record and returns the new {@code audit_event.id}. */
    UUID write(CommandContext command, AuditRecord record);
}
