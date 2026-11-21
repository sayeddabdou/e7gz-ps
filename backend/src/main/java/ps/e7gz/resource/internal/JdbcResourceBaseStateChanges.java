package ps.e7gz.resource.internal;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.audit.api.OwnerPasswordCheck;
import ps.e7gz.common.codes.ResourceBaseState;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.inventory.ledger.api.ActiveCommitments;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.resource.api.ResourceBaseStateChanges;

/** {@link ResourceBaseStateChanges}: {@link ResourceCommands#changeBaseState}, the one Disable / Archive write. */
@Component
class JdbcResourceBaseStateChanges implements ResourceBaseStateChanges {

    private final ActiveCommitments commitments;
    private final AuditWriter audit;
    private final Outbox outbox;

    JdbcResourceBaseStateChanges(ActiveCommitments commitments, AuditWriter audit, Outbox outbox) {
        this.commitments = commitments;
        this.audit = audit;
        this.outbox = outbox;
    }

    @Override
    public void change(CommandContext command, UUID venueAccountId, UUID venueId, UUID resourceId,
                       ResourceBaseState target, OwnerPasswordCheck check, UUID correlationId) {
        ResourceCommands.BaseStateAction action = switch (Objects.requireNonNull(target, "target")) {
            case DISABLED -> ResourceCommands.BaseStateAction.DISABLE;
            case ARCHIVED -> ResourceCommands.BaseStateAction.ARCHIVE;
            case ACTIVE -> throw new IllegalArgumentException("An Emergency never converts into ACTIVE");
        };
        ResourceCommands.changeBaseState(command, Objects.requireNonNull(venueAccountId, "venueAccountId"), venueId,
                resourceId, Objects.requireNonNull(correlationId, "correlationId"), action,
                Objects.requireNonNull(check, "check"), commitments, audit, outbox);
    }
}
