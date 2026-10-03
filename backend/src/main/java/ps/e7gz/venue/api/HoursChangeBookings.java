package ps.e7gz.venue.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.CommandContext;

/**
 * What the opening-hours impact preview needs to know about the Bookings behind affected reservation rows (FR-VEN-24,
 * FR-VEN-25). Declared here and implemented by the Booking module, so Venue never depends on Booking (Booking depends
 * on Venue for its Operating Sessions). Read-only, on the command's own transaction.
 */
public interface HoursChangeBookings {

    /** The facts of each Booking id that exists; unknown ids are left out. */
    Map<UUID, BookingFacts> facts(CommandContext command, Collection<UUID> bookingIds);

    /**
     * @param insideOperatingSession whether the Booking snapshotted an Operating Session; a Manual Booking made outside
     *                               published hours did not (FR-MAN-03) and is never affected by an hours change
     */
    record BookingFacts(UUID bookingId, BookingStatus status, BookingSource source, boolean insideOperatingSession) {

        /** A cancellation of it needs the Owner Password: a CONFIRMED Online Booking or an AWAITING one (FR-VEN-25). */
        public boolean protectedCancellation() {
            return status == BookingStatus.AWAITING_CUSTOMER_CONFIRMATION
                    || (status == BookingStatus.CONFIRMED && source == BookingSource.ONLINE);
        }
    }
}
