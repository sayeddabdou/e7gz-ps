package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Notification recipient type - {@code ck_notification_recipient_type}, {@code ck_notification_catalogue_values}
 * (recipient_types).
 */
@Schema(enumAsRef = true)
public enum NotificationRecipientType {
    CUSTOMER,
    VENUE,
    ADMIN
}
