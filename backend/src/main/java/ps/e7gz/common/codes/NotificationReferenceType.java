package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** What a catalogue notification refers to - {@code ck_notification_catalogue_values} (reference_type). */
@Schema(enumAsRef = true)
public enum NotificationReferenceType {
    BOOKING,
    TOURNAMENT,
    NONE
}
