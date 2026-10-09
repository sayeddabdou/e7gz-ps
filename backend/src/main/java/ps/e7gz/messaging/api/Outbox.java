package ps.e7gz.messaging.api;

import ps.e7gz.common.command.CommandContext;

/**
 * Publishes integration events through the transactional outbox (ADR-004, arch 08 section 4). Every call runs on the
 * command's own transaction: the event exists exactly when the business change that caused it commits, and never for a
 * command that rolls back (FR-NTF-12). Nothing is delivered here; the worker's dispatcher delivers it after commit.
 */
public interface Outbox {

    /**
     * Writes the event {@code PENDING} with the correlation ID of the current request or job, and wakes the dispatchers
     * when the command commits ({@code pg_notify('outbox')}). Returns the event id.
     */
    long publish(CommandContext context, NewOutboxEvent event);
}
