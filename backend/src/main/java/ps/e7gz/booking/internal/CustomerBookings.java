package ps.e7gz.booking.internal;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.venue.api.VenueContacts;

/**
 * The customer's bookings behind {@code /customer/bookings} (E7-49, arch 09 section 3.3): My Bookings by display
 * bucket (FR-BOK-19), one booking with its history (FR-BOK-20), and the customer's cancellation (FR-BOK-31). Every read
 * is one read-only command at the database's command instant, so the phone retention rule (arch 10 section 5.1) is
 * decided at the same "now" as every other timer; the cancel runs in one {@code CommandRunner} transaction wrapped by
 * E7-42's {@link Idempotency} (ADR-015): the transition, the release, history, fee decision, jobs, outbox event and the
 * Idempotency-Key record commit together or not at all.
 *
 * <p>Only the signed-in Customer's own Online Bookings: another customer's booking, or a Manual Booking, is
 * {@code NOT_FOUND} - for the cancel before any idempotency record is written (PO decision 11 Oct 2026, E7-49, D2).
 */
@Component
public class CustomerBookings {

    private final CommandRunner runner;
    private final Idempotency idempotency;
    private final CustomerBookingStore store;
    private final VenueAnswerStore bookings;
    private final BookingHistoryStore histories;
    private final VenueContacts venues;
    private final CustomerCancellation cancellation;

    CustomerBookings(CommandRunner runner, Idempotency idempotency, CustomerBookingStore store,
                     VenueAnswerStore bookings, BookingHistoryStore histories, VenueContacts venues,
                     CustomerCancellation cancellation) {
        this.runner = runner;
        this.idempotency = idempotency;
        this.store = store;
        this.bookings = bookings;
        this.histories = histories;
        this.venues = venues;
        this.cancellation = cancellation;
    }

    /** One page of the bucket: Upcoming soonest first, Completed and Cancelled most recent first. */
    public CustomerBookingRow.Page list(UUID customerId, DisplayBucket bucket, String cursor, Integer limit) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(bucket, "bucket");
        BookingCursor.Position after = BookingCursor.decode(cursor);
        int size = BookingCursor.size(limit);
        return runner.run(new Read<>(context -> {
            List<CustomerBookingStore.Row> rows = store.page(context, customerId, bucket, after, size + 1);
            boolean more = rows.size() > size;
            List<CustomerBookingRow> page = rows.stream().limit(size)
                    .map(row -> view(context, row, List.of())).toList();
            return new CustomerBookingRow.Page(page, more
                    ? BookingCursor.encode(new BookingCursor.Position(page.getLast().startAt(),
                            page.getLast().bookingId()))
                    : null);
        }));
    }

    /** One of the customer's bookings with its full history; another's is {@code NOT_FOUND}. */
    public CustomerBookingRow detail(UUID customerId, UUID bookingId) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(bookingId, "bookingId");
        return runner.run(new Read<>(context -> current(context, customerId, bookingId)));
    }

    /** Cancel once per Idempotency-Key; the answer is the booking as it is after the cancellation. */
    public <R> IdempotentResult<R> cancel(UUID customerId, UUID bookingId, IdempotentRequest key,
                                          StoredResponse<R> response, Function<CustomerBookingRow, R> view) {
        Objects.requireNonNull(view, "view");
        return runner.run(idempotency.wrap(Objects.requireNonNull(key, "key"), response,
                cancelCommand(customerId, bookingId, view)));
    }

    /** The same command without an Idempotency-Key record: package-private, for this package's tests. */
    CustomerBookingRow cancel(UUID customerId, UUID bookingId) {
        return runner.run(cancelCommand(customerId, bookingId, Function.identity()));
    }

    private <R> Command<R> cancelCommand(UUID customerId, UUID bookingId, Function<CustomerBookingRow, R> view) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(bookingId, "bookingId");
        FindBooking.Keys keys = runner.run(new FindBooking(bookings, bookingId))
                .filter(found -> customerId.equals(found.customerId()))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        return new Cancel<>(keys.scope(), context -> {
            cancellation.cancel(context, bookingId, customerId);
            return view.apply(current(context, customerId, bookingId));
        });
    }

    /** The booking as it is now on the command's transaction, with its history. */
    private CustomerBookingRow current(CommandContext context, UUID customerId, UUID bookingId) {
        CustomerBookingStore.Row row = store.one(context, customerId, bookingId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        return view(context, row, histories.forCustomer(context, bookingId));
    }

    /**
     * The customer's view of a row: the venue's name and phone, the phone only while the retention rule shows it (arch
     * 10 section 5.1; PO decision 11 Oct 2026, E7-49, D1) - absent otherwise. The answer deadline only while PENDING.
     */
    private CustomerBookingRow view(CommandContext context, CustomerBookingStore.Row row,
                                    List<BookingHistoryEntry> history) {
        VenueContacts.Contact venue = venues.find(context, row.venueId())
                .orElseThrow(() -> new IllegalStateException("Venue " + row.venueId() + " vanished"));
        String phone = ContactVisibility.visibleAt(row.contactFacts(), context.commandNow()) ? venue.phone() : null;
        return new CustomerBookingRow(row.bookingId(), row.status(), DisplayBucket.of(row.status()),
                row.terminalReason(), row.venueId(), venue.name(), phone, row.resourceName(), row.activity(),
                row.consoleType(), row.playMode(), row.startAt(), row.endAt(), row.nominalBookedMinutes(),
                row.venuePriceTotal(), row.bookingFee(), row.totalAmount(),
                row.status() == BookingStatus.PENDING ? row.venueResponseExpiresAt() : null,
                row.venueResponsePaused(), history);
    }

    /** The customer's cancel: category B (an existing commitment) under the booking's lock set. */
    private record Cancel<R>(CommandScope scope, Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
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
