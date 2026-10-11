package ps.e7gz.booking.internal;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;

/**
 * The booking history filters of the Venue Dashboard (FR-VEN-38); every member is optional. {@code from} and
 * {@code to} are business dates, inclusive (arch 04 section 4.3) - the presets (today, yesterday, last 7 days, this
 * month) are the dashboard's own. Without them the list covers all dates (PO decision 11 Oct 2026, E7-49, D4).
 *
 * <p>{@code q} (PO decision 11 Oct 2026, E7-49, D3 and its amendment) matches the exact booking ID, the customer's
 * exact current username (trimmed, case-insensitive, as username login; never a part of one), and a part of the
 * walk-in name or phone or of the resource name - never the customer's phone, which a search would otherwise reveal
 * past its retention point (NFR-18). Trimmed; blank is no search.
 */
public record VenueBookingFilter(LocalDate from, LocalDate to, Activity activity, BookingSource source,
                                 UUID resourceId, BookingStatus status, String q) {

    public VenueBookingFilter {
        q = q == null || q.isBlank() ? null : q.strip();
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "from");
        }
    }

    /** The booking ID {@code q} names exactly, if it is one. */
    Optional<UUID> bookingId() {
        if (q == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(q));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }

    /** {@code q} as a case-insensitive {@code LIKE} pattern matching it anywhere, its wildcards escaped. */
    String likePattern() {
        return "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
