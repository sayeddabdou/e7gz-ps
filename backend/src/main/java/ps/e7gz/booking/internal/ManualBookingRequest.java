package ps.e7gz.booking.internal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.validation.VenuePhones;

/**
 * A venue's walk-in or manual booking request (FR-MAN-01, F-16), its contact details already normalised.
 *
 * @param startAt       {@code null} for a walk-in starting now, at the command instant (FR-MAN-04)
 * @param customerName  trimmed; {@code null} when absent or blank
 * @param customerPhone E.164; {@code null} when absent or blank
 */
public record ManualBookingRequest(UUID resourceId, PlayMode playMode, Instant startAt, int durationMinutes,
                                   String customerName, String customerPhone) {

    public ManualBookingRequest {
        Objects.requireNonNull(resourceId, "resourceId");
    }

    /**
     * PO decision 10 Oct 2026, E7-48 (D10): the name and phone are trimmed and a blank one is absent; a phone is
     * normalised to E.164 like the venue phone ({@link VenuePhones}, PO decision 2 Oct 2026).
     *
     * @throws ApiException {@code VALIDATION_FAILED} on {@code customerPhone} when it is not an Egyptian number
     */
    public static ManualBookingRequest of(UUID resourceId, PlayMode playMode, Instant startAt, int durationMinutes,
                                          String customerName, String customerPhone) {
        String phone = blankToNull(customerPhone);
        return new ManualBookingRequest(resourceId, playMode, startAt, durationMinutes, blankToNull(customerName),
                phone == null ? null : VenuePhones.toE164(phone)
                        .orElseThrow(() -> new ApiException(ApiErrorCode.VALIDATION_FAILED, "customerPhone")));
    }

    /** A walk-in: no start time, so it starts at the command instant. */
    boolean walkIn() {
        return startAt == null;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
