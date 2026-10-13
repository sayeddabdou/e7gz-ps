package ps.e7gz.notification.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.LockSet;

/**
 * Read-only: what the booking notifications need to know about one Booking (E7-55; PO decision 13 Oct 2026: a small
 * read supplied by the booking module, reviewed by the Engine lane). Implemented by the booking module: the
 * interface lives here, not in {@code booking.api}, because the venue module already uses this module (owner password
 * reset, Web Push) and the booking module uses the venue module, so a notification -> booking dependency would be a
 * cycle ({@code ArchitectureTest}).
 *
 * <p>The reminders - the venue's "request expiring" and the customer's "starts soon" - re-read the booking when they
 * fire and supersede themselves when it moved on (arch 08 section 2.2, boundary rule), so no booking command has to
 * withdraw them. Runs on the caller's command transaction.
 */
public interface NotifiedBookings {

    /** The booking as it is now in the caller's transaction; empty when there is no such booking. */
    Optional<NotifiedBooking> find(CommandContext command, UUID bookingId);

    /**
     * @param customerId             {@code null} for a Manual Booking without a Customer
     * @param venueName              the venue's name now (the customer app shows it, E7-51)
     * @param venueResponseExpiresAt the stored response deadline of an Online Booking; {@code null} for a Manual one
     * @param responsePaused         the venue-response timer is paused (FR-OPS-34)
     */
    record NotifiedBooking(UUID bookingId, UUID customerId, UUID venueId, String venueName, UUID resourceId,
                           BookingSource source, BookingStatus status, Instant startAt,
                           Instant venueResponseExpiresAt, boolean responsePaused) {

        public NotifiedBooking {
            Objects.requireNonNull(bookingId, "bookingId");
            Objects.requireNonNull(venueId, "venueId");
            Objects.requireNonNull(venueName, "venueName");
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(startAt, "startAt");
        }

        /**
         * The booking's Customer, Venue and Resource - the locks of the venue's answer, the customer's cancel and T1,
         * so a reminder fired under them sees the booking's state settled.
         */
        public CommandScope scope() {
            LockSet.Builder locks = LockSet.builder().venues(venueId).resources(resourceId);
            if (customerId != null) {
                locks.users(customerId);
            }
            return CommandScope.shared(locks.build());
        }

        /** The same booking with another start (tests hit the reminder's boundaries with it; start_at is immutable). */
        public NotifiedBooking withStartAt(Instant otherStart) {
            return new NotifiedBooking(bookingId, customerId, venueId, venueName, resourceId, source, status,
                    otherStart, venueResponseExpiresAt, responsePaused);
        }
    }
}
