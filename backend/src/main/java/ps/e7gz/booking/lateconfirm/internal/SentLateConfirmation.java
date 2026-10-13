package ps.e7gz.booking.lateconfirm.internal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.BookingStatus;

/** The booking after the venue sent its Late Confirmation: AWAITING_CUSTOMER_CONFIRMATION until the deadline. */
public record SentLateConfirmation(UUID bookingId, BookingStatus status, Instant lateConfirmExpiresAt) {

    public SentLateConfirmation {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(lateConfirmExpiresAt, "lateConfirmExpiresAt");
    }
}
