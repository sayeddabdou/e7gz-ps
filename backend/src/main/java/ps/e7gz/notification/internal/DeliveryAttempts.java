package ps.e7gz.notification.internal;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.codes.NotificationDeliveryStatus;

/**
 * Records one delivery attempt through {@code notification.record_delivery_attempt}, the only writer of a delivery's
 * lifecycle (the guard trigger refuses any other update): {@code PENDING}/{@code FAILED} to {@code SENT},
 * {@code FAILED} or {@code DEAD_LETTER}, with {@code attempt + 1}.
 */
final class DeliveryAttempts {

    private DeliveryAttempts() {
    }

    static void record(JdbcClient jdbc, UUID deliveryId, DeliveryOutcome outcome) {
        record(jdbc, deliveryId, outcome, false);
    }

    /**
     * @param lastAttempt a transient failure on the last attempt is final too: {@code DEAD_LETTER}, which only the admin
     *                    "try again" reopens
     */
    static void record(JdbcClient jdbc, UUID deliveryId, DeliveryOutcome outcome, boolean lastAttempt) {
        NotificationDeliveryStatus status;
        String messageId = null;
        String error = null;
        switch (outcome) {
            case DeliveryOutcome.Sent sent -> {
                status = NotificationDeliveryStatus.SENT;
                messageId = sent.providerMessageId();
            }
            case DeliveryOutcome.Transient failure -> {
                status = lastAttempt ? NotificationDeliveryStatus.DEAD_LETTER : NotificationDeliveryStatus.FAILED;
                error = failure.errorCode();
            }
            case DeliveryOutcome.Permanent failure -> {
                status = NotificationDeliveryStatus.DEAD_LETTER;
                error = failure.errorCode();
            }
        }
        jdbc.sql("SELECT notification.record_delivery_attempt(:id, :outcome, :messageId, :error)")
                .param("id", deliveryId).param("outcome", status.name())
                .param("messageId", messageId).param("error", error)
                .query().singleRow();
    }
}
