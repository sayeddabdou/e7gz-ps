package ps.e7gz.booking.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.BookingEventType;
import ps.e7gz.common.codes.BookingHistoryEntryKind;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.command.CommandContext;

/**
 * The append-only history of one booking, oldest first (FR-AUD-05), as each surface may see it.
 *
 * <p>The venue's view never contains {@code LATE_CONFIRM_FAILED_BOOKING_LIMIT} or
 * {@code LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT}: a venue must never learn why a customer is ineligible for a Late
 * Confirmation, nor that the customer has another booking (NFR-19, arch 10 section 5.2, FR-LCF-05, FR-LCF-15). They are
 * left out in the SQL itself, so no venue code path can read them.
 */
@Component
class BookingHistoryStore {

    /** NFR-19: the Booking Events the venue surface never shows. */
    static final List<BookingEventType> HIDDEN_FROM_VENUE = List.of(
            BookingEventType.LATE_CONFIRM_FAILED_BOOKING_LIMIT,
            BookingEventType.LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT);

    static final String CUSTOMER = """
            SELECT h.occurred_at, h.entry_kind, h.from_status, h.to_status, h.terminal_reason, h.event_type
              FROM booking.booking_history h
             WHERE h.booking_id = :bookingId
             ORDER BY h.sequence
            """;

    static final String VENUE = """
            SELECT h.occurred_at, h.entry_kind, h.from_status, h.to_status, h.terminal_reason, h.event_type
              FROM booking.booking_history h
             WHERE h.booking_id = :bookingId
               AND (h.event_type IS NULL OR h.event_type NOT IN (:hidden))
             ORDER BY h.sequence
            """;

    List<BookingHistoryEntry> forCustomer(CommandContext command, UUID bookingId) {
        return command.jdbc().sql(CUSTOMER)
                .param("bookingId", Objects.requireNonNull(bookingId, "bookingId"))
                .query(BookingHistoryStore::entry)
                .list();
    }

    /** NFR-19: without the Late Confirm failure events. */
    List<BookingHistoryEntry> forVenue(CommandContext command, UUID bookingId) {
        return command.jdbc().sql(VENUE)
                .param("bookingId", Objects.requireNonNull(bookingId, "bookingId"))
                .param("hidden", HIDDEN_FROM_VENUE.stream().map(Enum::name).toList())
                .query(BookingHistoryStore::entry)
                .list();
    }

    private static BookingHistoryEntry entry(ResultSet rs, int n) throws SQLException {
        String from = rs.getString("from_status");
        String to = rs.getString("to_status");
        String reason = rs.getString("terminal_reason");
        String event = rs.getString("event_type");
        return new BookingHistoryEntry(rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                BookingHistoryEntryKind.valueOf(rs.getString("entry_kind")),
                from == null ? null : BookingStatus.valueOf(from),
                to == null ? null : BookingStatus.valueOf(to),
                reason == null ? null : BookingTerminalReason.valueOf(reason),
                event == null ? null : BookingEventType.valueOf(event));
    }
}
