package ps.e7gz.booking.internal;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;

/**
 * Read-only: the rows a booking command names in its lock set - the booking's Customer, Venue and Resource - read
 * before that command, because {@code begin_command} takes them up front (arch 05 section 4.2). A booking never changes
 * Customer, Venue or Resource ({@code tr_10_booking_historical_snapshot_immutable}), so the answer still holds when the
 * command runs and re-reads the booking's state under the locks.
 */
record FindBooking(VenueAnswerStore store, UUID bookingId) implements Command<Optional<FindBooking.Keys>> {

    FindBooking {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(bookingId, "bookingId");
    }

    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
    }

    /** Locks nothing: one read, then the command commits. */
    @Override
    public CommandScope scope() {
        return CommandScope.shared(LockSet.empty());
    }

    @Override
    public Optional<Keys> execute(CommandContext context) {
        return store.facts(context, bookingId)
                .map(facts -> new Keys(facts.bookingId(), facts.venueId(), facts.resourceId(), facts.customerId()));
    }

    /** @param customerId {@code null} for a Manual Booking without a Customer */
    record Keys(UUID bookingId, UUID venueId, UUID resourceId, UUID customerId) {

        Keys {
            Objects.requireNonNull(bookingId, "bookingId");
            Objects.requireNonNull(venueId, "venueId");
            Objects.requireNonNull(resourceId, "resourceId");
        }

        /** The Customer (with their Bookings, commitment guard and Bans), the Venue and the Resource. */
        CommandScope scope() {
            LockSet.Builder locks = LockSet.builder().venues(venueId).resources(resourceId);
            if (customerId != null) {
                locks.users(customerId);
            }
            return CommandScope.shared(locks.build());
        }
    }
}
