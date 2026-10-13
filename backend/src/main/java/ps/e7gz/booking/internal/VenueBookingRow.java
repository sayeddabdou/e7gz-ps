package ps.e7gz.booking.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.money.Money;

/**
 * A booking of the signed-in venue as the Venue Dashboard sees it (arch 09 section 4.1), read on a command's
 * transaction after its own writes. The customer's phone is already decided by the counterpart visibility rule
 * ({@link ContactVisibility}, arch 10 section 5.1): {@code null} when hidden, so the DTO leaves it out.
 *
 * @param venueResponseExpiresAt the stored answer deadline while PENDING, otherwise {@code null}
 * @param lateConfirmExpiresAt   the open Late Confirmation's stored deadline while AWAITING_CUSTOMER_CONFIRMATION,
 *                               otherwise {@code null} (E7-58)
 * @param lateConfirmPaused      an emergency pauses that deadline now (FR-OPS-41); never its cause
 * @param customerUsername       ONLINE only
 * @param walkInName             MANUAL only, as the venue typed it (FR-DIS-09)
 */
public record VenueBookingRow(UUID bookingId, UUID venueId, BookingSource source, BookingStatus status,
                              BookingTerminalReason terminalReason, String rejectReasonText, UUID resourceId,
                              String resourceName, Activity activity, ConsoleType consoleType, PlayMode playMode,
                              Instant startAt, Instant endAt, LocalDate businessDate, int nominalBookedMinutes,
                              Money venuePriceTotal, Instant venueResponseExpiresAt, boolean venueResponsePaused,
                              Instant lateConfirmExpiresAt, boolean lateConfirmPaused, String customerUsername,
                              String customerPhone, String walkInName, String walkInPhone,
                              Instant createdAt) {

    public VenueBookingRow {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(resourceName, "resourceName");
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(endAt, "endAt");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(venuePriceTotal, "venuePriceTotal");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
