package ps.e7gz.booking.internal;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.internal.VenueAnswerStore.ViewRow;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.identity.api.CustomerContacts;
import ps.e7gz.venue.api.VenueAccountVenues;

/**
 * The venue's reads of its bookings for E7-47 - the Pending and Missed request lists (FR-VEN-34, FR-BOK-20) - and the
 * {@link VenueBookingRow} every venue action answers with. The customer's phone goes through the counterpart
 * visibility rule at the read instant ({@link ContactVisibility}, arch 10 section 5.1, NFR-18): absent when hidden.
 */
@Component
public class VenueBookings {

    private final CommandRunner runner;
    private final VenueAnswerStore store;
    private final CustomerContacts customers;
    private final VenueAccountVenues accounts;

    VenueBookings(CommandRunner runner, VenueAnswerStore store, CustomerContacts customers,
                  VenueAccountVenues accounts) {
        this.runner = runner;
        this.store = store;
        this.customers = customers;
        this.accounts = accounts;
    }

    /** PENDING requests of the signed-in account's venue, soonest deadline first. */
    public List<VenueBookingRow> pending(UUID venueAccountId) {
        UUID venueId = accounts.venueOf(Objects.requireNonNull(venueAccountId, "venueAccountId"));
        return runner.run(new Read<>(context -> store.pending(context, venueId).stream()
                .map(row -> row(context, row)).toList()));
    }

    /** VENUE_RESPONSE_EXPIRED requests of the venue whose start has not come yet (strictly {@code now < startAt}). */
    public List<VenueBookingRow> missed(UUID venueAccountId) {
        UUID venueId = accounts.venueOf(Objects.requireNonNull(venueAccountId, "venueAccountId"));
        return runner.run(new Read<>(context -> store.missed(context, venueId, context.commandNow()).stream()
                .map(row -> row(context, row)).toList()));
    }

    /** The booking as it is now on the command's transaction, after the command's own writes. */
    VenueBookingRow current(CommandContext context, UUID bookingId) {
        return row(context, store.view(context, bookingId)
                .orElseThrow(() -> new IllegalStateException("Booking " + bookingId + " vanished")));
    }

    /** Whether the venue may see the customer's phone of {@code bookingId} now; hidden when unknown. */
    public boolean customerPhoneVisible(CommandContext context, UUID bookingId) {
        return store.view(context, bookingId)
                .map(row -> ContactVisibility.visibleAt(row.contactFacts(), context.commandNow()))
                .orElse(false);
    }

    private VenueBookingRow row(CommandContext context, ViewRow row) {
        String username = null;
        String phone = null;
        if (row.source() == BookingSource.ONLINE) {
            CustomerContacts.Contact customer = customers.find(context, row.customerId())
                    .orElseThrow(() -> new IllegalStateException("Customer " + row.customerId() + " vanished"));
            username = customer.username();
            phone = ContactVisibility.visibleAt(row.contactFacts(), context.commandNow()) ? customer.phone() : null;
        }
        return new VenueBookingRow(row.bookingId(), row.venueId(), row.source(), row.status(), row.terminalReason(),
                row.rejectReasonText(), row.resourceId(), row.resourceName(), row.activity(), row.consoleType(),
                row.playMode(), row.startAt(), row.endAt(), row.businessDate(), row.nominalBookedMinutes(),
                row.venuePriceTotal(), row.status() == BookingStatus.PENDING ? row.venueResponseExpiresAt() : null,
                row.venueResponsePaused(), username, phone, row.walkInName(), row.walkInPhone(), row.createdAt());
    }

    /** A read-only command: one snapshot and the database's instant, no locks. */
    private record Read<R>(Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }
}
