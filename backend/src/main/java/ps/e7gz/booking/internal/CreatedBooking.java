package ps.e7gz.booking.internal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.loyalty.api.BookingFeePayments.FeePayment;

/**
 * A committed PENDING Online Booking: what the response and the {@code BookingCreated} event are built from. The
 * Resource fields are the booking's snapshots; {@code venuePhone} is visible because the booking is PENDING (FR-DIS-09,
 * arch 10 section 5.1) - a replay re-applies the rule ({@link ContactVisibility}). {@code feePayment} is how Points
 * and the Wallet paid the fee in the creating transaction (PCR-009, PCR-010).
 */
public record CreatedBooking(UUID bookingId, UUID reservationId, UUID customerId, UUID venueId, UUID resourceId,
                             Instant startAt, Instant endAt, Instant venueResponseExpiresAt, BookingAmounts amounts,
                             Instant createdAt, String venueName, String venuePhone, String resourceName,
                             Activity activity, ConsoleType consoleType, PlayMode playMode, FeePayment feePayment) {

    public CreatedBooking {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(endAt, "endAt");
        Objects.requireNonNull(venueResponseExpiresAt, "venueResponseExpiresAt");
        Objects.requireNonNull(amounts, "amounts");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(venueName, "venueName");
        Objects.requireNonNull(venuePhone, "venuePhone");
        Objects.requireNonNull(resourceName, "resourceName");
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(feePayment, "feePayment");
    }
}
