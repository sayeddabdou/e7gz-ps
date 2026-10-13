package ps.e7gz.booking.internal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.money.Money;

/**
 * One of the customer's bookings as the Customer App sees it (arch 09 section 3.3), read at the command instant. The
 * venue phone is already decided by the counterpart visibility rule ({@link ContactVisibility}, arch 10 section 5.1):
 * {@code null} when hidden, so the DTO leaves it out.
 *
 * @param venueResponseExpiresAt the stored answer deadline while PENDING, otherwise {@code null}
 * @param lateConfirmExpiresAt   the open Late Confirmation's stored deadline while AWAITING_CUSTOMER_CONFIRMATION,
 *                               otherwise {@code null} (E7-58)
 * @param lateConfirmPaused      an emergency pauses that deadline now (FR-OPS-41); never its cause
 * @param history                oldest first; empty in a list (only the detail carries it)
 */
public record CustomerBookingRow(UUID bookingId, BookingStatus status, DisplayBucket displayBucket,
                                 BookingTerminalReason terminalReason, UUID venueId, String venueName,
                                 String venuePhone, String resourceName, Activity activity, ConsoleType consoleType,
                                 PlayMode playMode, Instant startAt, Instant endAt, int nominalBookedMinutes,
                                 Money venuePriceTotal, Money bookingFee, Money totalAmount,
                                 Instant venueResponseExpiresAt, boolean venueResponsePaused,
                                 Instant lateConfirmExpiresAt, boolean lateConfirmPaused,
                                 List<BookingHistoryEntry> history) {

    public CustomerBookingRow {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(displayBucket, "displayBucket");
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(venueName, "venueName");
        Objects.requireNonNull(resourceName, "resourceName");
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(endAt, "endAt");
        Objects.requireNonNull(venuePriceTotal, "venuePriceTotal");
        Objects.requireNonNull(bookingFee, "bookingFee");
        Objects.requireNonNull(totalAmount, "totalAmount");
        history = List.copyOf(history);
    }

    /** A page of My Bookings; {@code nextCursor} is null on the last page. */
    public record Page(List<CustomerBookingRow> items, String nextCursor) {

        public Page {
            items = List.copyOf(items);
        }
    }
}
