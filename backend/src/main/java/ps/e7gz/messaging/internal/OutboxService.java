package ps.e7gz.messaging.internal;

import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.NewOutboxEvent;
import ps.e7gz.messaging.api.Outbox;

/**
 * {@link Outbox} on the command's own transaction (every profile: commands publish, the worker dispatches). The database
 * sends no {@code NOTIFY} itself, so the writer calls {@code pg_notify('outbox')} in the same transaction: PostgreSQL
 * delivers it only at commit (arch 08 section 4.2), as {@code ScheduledJobs} does for {@code 'sched'}.
 */
@Component
class OutboxService implements Outbox {

    private final JdbcOutboxStore store;

    OutboxService(JdbcOutboxStore store) {
        this.store = store;
    }

    @Override
    public long publish(CommandContext context, NewOutboxEvent event) {
        long id = store.insert(context.jdbc(), event, CorrelationId.require(), context.commandNow());
        store.wakeDispatchers(context.jdbc());
        return id;
    }
}
