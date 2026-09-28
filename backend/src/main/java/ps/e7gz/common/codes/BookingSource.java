package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Where a booking came from - {@code ck_booking_source_values}. */
@Schema(enumAsRef = true)
public enum BookingSource {
    ONLINE,
    MANUAL
}
