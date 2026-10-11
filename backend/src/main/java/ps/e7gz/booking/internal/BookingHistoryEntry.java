package ps.e7gz.booking.internal;

import java.time.Instant;
import java.util.Objects;
import ps.e7gz.common.codes.BookingEventType;
import ps.e7gz.common.codes.BookingHistoryEntryKind;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;

/**
 * One {@code booking.booking_history} row as a surface shows it (FR-AUD-05): a status transition, with the terminal
 * reason when it ended the booking, or a Booking Event. Codes only; the clients own the text (ADR-021).
 */
public record BookingHistoryEntry(Instant at, BookingHistoryEntryKind kind, BookingStatus fromStatus,
                                  BookingStatus toStatus, BookingTerminalReason terminalReason,
                                  BookingEventType eventType) {

    public BookingHistoryEntry {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(kind, "kind");
    }
}
