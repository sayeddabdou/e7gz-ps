package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * How the Booking Fee is charged (PCR-010, FR-PTS-13 as amended) - {@code ck_fee_points_payment_shape} (fee_mode) and
 * the {@code booking.fee_mode} configuration key's allowed values: one fee per booking, or a fee per hour of the
 * booked nominal duration.
 */
@Schema(enumAsRef = true)
public enum BookingFeeMode {
    PER_BOOKING,
    PER_HOUR
}
