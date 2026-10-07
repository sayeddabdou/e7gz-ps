package ps.e7gz.inventory.availability.api;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;

/**
 * What the Venue Dashboard schedule needs to know about the Bookings behind its {@code BOOKING} rows (FR-VEN-37,
 * E7-112): status and source. Declared here and implemented by the Booking module, so Availability never depends on
 * Booking (arch 03 section 2.1: Booking is a higher layer; the same pattern as {@link DiscoveryRatings} and
 * {@code venue.api.HoursChangeBookings}). Read-only.
 */
public interface ScheduleBookings {

    /** The facts of every given Booking that exists, in one read; unknown ids are left out. */
    Map<UUID, Facts> facts(Collection<UUID> bookingIds);

    record Facts(UUID bookingId, BookingStatus status, BookingSource source) {

        public Facts {
            Objects.requireNonNull(bookingId, "bookingId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(source, "source");
        }
    }
}
