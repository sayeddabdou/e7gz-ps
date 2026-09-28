package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Venue marketplace status — {@code ck_venue_marketplace_status} (FR-VEN-06, FR-VEN-07). */
@Schema(enumAsRef = true)
public enum MarketplaceStatus {
    ONBOARDING,
    ACTIVE,
    INACTIVE,
    SUSPENDED
}
