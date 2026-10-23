package ps.e7gz.booking.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.AttendanceEvidence;
import ps.e7gz.booking.api.LateConfirmWindows;
import ps.e7gz.booking.internal.VenueAnswerStore.ViewRow;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.identity.api.CustomerContacts;
import ps.e7gz.venue.api.VenueAccountVenues;

/**
 * The venue's reads of its bookings - the Pending and Missed request lists of E7-47 (FR-VEN-34, FR-BOK-20), the
 * booking history list and one booking with its history of E7-49 (FR-VEN-38) - and the {@link VenueBookingRow} every
 * venue action answers with. The customer's phone goes through the counterpart visibility rule at the read instant
 * ({@link ContactVisibility}, arch 10 section 5.1, NFR-18): absent when hidden.
 */
@Component
public class VenueBookings {

    /** A booking without an attendance record: no action yet (E7-59). */
    private static final AttendanceEvidence.Recorded NO_ATTENDANCE = new AttendanceEvidence.Recorded(null, null, null,
            null);

    private final CommandRunner runner;
    private final VenueAnswerStore store;
    private final CustomerContacts customers;
    private final VenueAccountVenues accounts;
    private final BookingHistoryStore histories;
    private final LateConfirmWindows lateConfirms;
    private final AttendanceEvidence attendance;

    VenueBookings(CommandRunner runner, VenueAnswerStore store, CustomerContacts customers,
                  VenueAccountVenues accounts, BookingHistoryStore histories, LateConfirmWindows lateConfirms,
                  AttendanceEvidence attendance) {
        this.runner = runner;
        this.store = store;
        this.customers = customers;
        this.accounts = accounts;
        this.histories = histories;
        this.lateConfirms = lateConfirms;
        this.attendance = attendance;
    }

    /** A page of the venue's bookings; {@code nextCursor} is null on the last page. */
    public record Page(List<VenueBookingRow> items, String nextCursor) {

        public Page {
            items = List.copyOf(items);
        }
    }

    /** One booking with the history the venue may see, oldest first (FR-AUD-05, NFR-19). */
    public record Detail(VenueBookingRow booking, List<BookingHistoryEntry> history) {

        public Detail {
            Objects.requireNonNull(booking, "booking");
            history = List.copyOf(history);
        }
    }

    /** PENDING requests of the signed-in account's venue, soonest deadline first. */
    public List<VenueBookingRow> pending(UUID venueAccountId) {
        UUID venueId = accounts.venueOf(Objects.requireNonNull(venueAccountId, "venueAccountId"));
        return runner.run(new Read<>(context -> rows(context, store.pending(context, venueId))));
    }

    /** VENUE_RESPONSE_EXPIRED requests of the venue whose start has not come yet (strictly {@code now < startAt}). */
    public List<VenueBookingRow> missed(UUID venueAccountId) {
        UUID venueId = accounts.venueOf(Objects.requireNonNull(venueAccountId, "venueAccountId"));
        return runner.run(new Read<>(context -> rows(context, store.missed(context, venueId, context.commandNow()))));
    }

    /**
     * One page of the venue's booking history (FR-VEN-38, E7-49), newest first; without dates, all dates (PO decision
     * 11 Oct 2026, E7-49, D4). Every source and status of the signed-in account's own venue only.
     *
     * <p>{@code q} names a Customer only by their exact current username (PO decision 11 Oct 2026, E7-49, D3
     * amendment): one Identity lookup per search, with username login's trimming and case rule; never a partial
     * username and never a phone. Usernames are Identity's ({@code iam.user_account}), so they are read through its
     * API, never by joining its table. The Customer found matters only through this venue's own bookings.
     */
    public Page list(UUID venueAccountId, VenueBookingFilter filter, String cursor, Integer limit) {
        UUID venueId = accounts.venueOf(Objects.requireNonNull(venueAccountId, "venueAccountId"));
        Objects.requireNonNull(filter, "filter");
        BookingCursor.Position after = BookingCursor.decode(cursor);
        int size = BookingCursor.size(limit);
        return runner.run(new Read<>(context -> {
            UUID usernameMatch = filter.q() == null ? null
                    : customers.findByUsername(context, filter.q()).orElse(null);
            List<ViewRow> rows = store.page(context, venueId, filter, usernameMatch, after, size + 1);
            List<VenueBookingRow> page = rows(context, rows.stream().limit(size).toList());
            return new Page(page, rows.size() > size
                    ? BookingCursor.encode(new BookingCursor.Position(page.getLast().startAt(),
                            page.getLast().bookingId()))
                    : null);
        }));
    }

    /** One booking of the venue with its history, without what NFR-19 hides; another venue's is NOT_FOUND. */
    public Detail detail(UUID venueAccountId, UUID bookingId) {
        UUID venueId = accounts.venueOf(Objects.requireNonNull(venueAccountId, "venueAccountId"));
        Objects.requireNonNull(bookingId, "bookingId");
        return runner.run(new Read<>(context -> {
            ViewRow row = store.view(context, bookingId)
                    .filter(found -> found.venueId().equals(venueId))
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            return new Detail(rows(context, List.of(row)).getFirst(), histories.forVenue(context, bookingId));
        }));
    }

    /** The booking as it is now on the command's transaction, after the command's own writes. */
    VenueBookingRow current(CommandContext context, UUID bookingId) {
        return rows(context, List.of(store.view(context, bookingId)
                .orElseThrow(() -> new IllegalStateException("Booking " + bookingId + " vanished")))).getFirst();
    }

    /** Whether the venue may see the customer's phone of {@code bookingId} now; hidden when unknown. */
    public boolean customerPhoneVisible(CommandContext context, UUID bookingId) {
        return store.view(context, bookingId)
                .map(row -> ContactVisibility.visibleAt(row.contactFacts(), context.commandNow()))
                .orElse(false);
    }

    /**
     * The venue's view of {@code rows}. The open Late Confirmation deadlines of the AWAITING ones come from the Late
     * Confirmation module in one read for the whole list ({@link LateConfirmWindows}, E7-58), never one per row; the
     * attendance records likewise from the Attendance module ({@link AttendanceEvidence}, E7-59).
     */
    private List<VenueBookingRow> rows(CommandContext context, List<ViewRow> rows) {
        Map<UUID, Instant> deadlines = lateConfirms.openDeadlines(context, rows.stream()
                .filter(row -> row.status() == BookingStatus.AWAITING_CUSTOMER_CONFIRMATION)
                .map(ViewRow::bookingId).toList());
        Map<UUID, AttendanceEvidence.Recorded> recorded = attendance.of(context,
                rows.stream().map(ViewRow::bookingId).toList());
        return rows.stream().map(row -> row(context, row, deadlines.get(row.bookingId()),
                recorded.getOrDefault(row.bookingId(), NO_ATTENDANCE))).toList();
    }

    private VenueBookingRow row(CommandContext context, ViewRow row, Instant lateConfirmExpiresAt,
                                AttendanceEvidence.Recorded recorded) {
        boolean awaiting = row.status() == BookingStatus.AWAITING_CUSTOMER_CONFIRMATION;
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
                row.venueResponsePaused(), awaiting ? lateConfirmExpiresAt : null, awaiting && row.lateConfirmPaused(),
                username, phone, row.walkInName(), row.walkInPhone(), recorded.result(), recorded.checkedInAt(),
                recorded.latePresentAt(), recorded.noShowAt(), row.createdAt());
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
