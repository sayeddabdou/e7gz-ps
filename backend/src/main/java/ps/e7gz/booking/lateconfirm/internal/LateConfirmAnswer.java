package ps.e7gz.booking.lateconfirm.internal;

import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;

/**
 * The booking after the customer's answer: CONFIRMED after an Accept, or CANCELLED with CUSTOMER_DECLINED_LATE_CONFIRM
 * after a Decline ({@code terminalReason} is null otherwise).
 */
public record LateConfirmAnswer(UUID bookingId, BookingStatus status, BookingTerminalReason terminalReason) {

    public LateConfirmAnswer {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(status, "status");
    }
}
