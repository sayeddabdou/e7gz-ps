package ps.e7gz.notification.internal;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;
import ps.e7gz.messaging.api.Outbox;

/**
 * The Platform Admin's "try again" for one dead-lettered notification delivery (arch 08 section 4.3; PO decisions 9 and
 * 10 Oct 2026, E7-41/E7-43): {@code notification.recover_dead_letter} moves it back to {@code PENDING} and writes the
 * audit row (admin runtime only, inside {@code admin.begin_command}), and a new outbox event asks the worker to send it
 * again, with a fresh set of attempts. Only deliveries are recovered: an outbox event in {@code DEAD_LETTER} stays final.
 */
@Component
public class DeliveryRecovery {

    private final CommandRunner runner;
    private final Outbox outbox;

    DeliveryRecovery(CommandRunner runner, Outbox outbox) {
        this.runner = runner;
        this.outbox = outbox;
    }

    /**
     * @throws ApiException {@code NOT_FOUND} for an unknown delivery; {@code NOTIFICATION_DELIVERY_NOT_DEAD_LETTER} when
     *                      it is not {@code DEAD_LETTER} (sent, or still being retried)
     */
    public void recover(UUID adminId, UUID deliveryId, String reason) {
        runner.run(new Recover(adminId, CorrelationId.require(), deliveryId, reason, outbox));
    }

    private record Recover(UUID adminId, UUID correlationId, UUID deliveryId, String reason, Outbox outbox)
            implements Command<Void> {

        Recover {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.admin(adminId, correlationId, LockSet.empty());
        }

        @Override
        public Void execute(CommandContext context) {
            record Found(UUID notificationId, String status) {
            }
            Found delivery = context.jdbc().sql("""
                            SELECT notification_id, status FROM notification.notification_delivery
                             WHERE id = :id FOR UPDATE""")
                    .param("id", deliveryId)
                    .query((rs, row) -> new Found(rs.getObject("notification_id", UUID.class),
                            rs.getString("status")))
                    .optional()
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            if (!"DEAD_LETTER".equals(delivery.status())) {
                throw new ApiException(ApiErrorCode.NOTIFICATION_DELIVERY_NOT_DEAD_LETTER);
            }
            context.jdbc().sql("SELECT notification.recover_dead_letter(:id, :reason)")
                    .param("id", deliveryId).param("reason", reason.strip()).query().singleRow();
            outbox.publish(context, new NewOutboxEvent(IntegrationEvents.NOTIFICATION_DELIVERY_REQUESTED,
                    "NOTIFICATION", delivery.notificationId(),
                    Map.of("notificationId", delivery.notificationId().toString())));
            return null;
        }
    }
}
