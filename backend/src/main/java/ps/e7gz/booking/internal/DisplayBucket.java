package ps.e7gz.booking.internal;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import ps.e7gz.common.codes.BookingStatus;

/**
 * The customer-facing projection of a booking's status (arch 06 section 1.5, FR-BOK-19). A display mapping only, never
 * a state change: VENUE_RESPONSE_EXPIRED is shown under Cancelled while it stays internally revivable (FR-BOK-20).
 */
public enum DisplayBucket {

    UPCOMING,
    COMPLETED,
    CANCELLED;

    /** FR-BOK-19, exhaustively: every status has exactly one bucket. */
    public static DisplayBucket of(BookingStatus status) {
        return switch (status) {
            case PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED, ACTIVE -> UPCOMING;
            case COMPLETED -> COMPLETED;
            case VENUE_RESPONSE_EXPIRED, CANCELLED, REJECTED, NO_SHOW -> CANCELLED;
        };
    }

    /** The statuses shown under this bucket. */
    public Set<BookingStatus> statuses() {
        Set<BookingStatus> statuses = EnumSet.noneOf(BookingStatus.class);
        Arrays.stream(BookingStatus.values()).filter(status -> of(status) == this).forEach(statuses::add);
        return statuses;
    }

    /** Upcoming lists the soonest first; Completed and Cancelled the most recent first. */
    boolean soonestFirst() {
        return this == UPCOMING;
    }
}
