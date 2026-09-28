package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Notification delivery status - {@code ck_notification_delivery_status}. */
@Schema(enumAsRef = true)
public enum NotificationDeliveryStatus {
    PENDING,
    SENT,
    FAILED,
    DEAD_LETTER
}
