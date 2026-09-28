package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Venue account status — {@code ck_venue_account_status}; independent of marketplace status (FR-VEN-06, FR-VEN-08). */
@Schema(enumAsRef = true)
public enum VenueAccountStatus {
    ENABLED,
    DISABLED
}
