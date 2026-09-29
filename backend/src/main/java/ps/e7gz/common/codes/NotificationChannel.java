package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Notification delivery channel - {@code ck_notification_delivery_channel}, {@code
 * ck_notification_catalogue_values} (allowed_channels).
 */
@Schema(enumAsRef = true)
public enum NotificationChannel {
    IN_APP,
    PUSH,
    EMAIL,
    SMS
}
