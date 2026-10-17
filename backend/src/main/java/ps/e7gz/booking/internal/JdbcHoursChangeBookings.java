package ps.e7gz.booking.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.LateConfirmWindows;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.venue.api.HoursChangeBookings;

/**
 * The Booking side of an opening-hours change, on the command's own transaction: the facts the impact preview reads
 * (E7-25) and the cancellation of the affected set the commit hands over (E7-113, {@link HoursChangeCancellation}).
 */
@Component
class JdbcHoursChangeBookings implements HoursChangeBookings {

    private final HoursChangeCancellation cancellation;
    private final LateConfirmWindows windows;

    JdbcHoursChangeBookings(HoursChangeCancellation cancellation, LateConfirmWindows windows) {
        this.cancellation = cancellation;
        this.windows = windows;
    }

    /**
     * The facts of the bookings, under the command's locks. An AWAITING booking's open Late Confirmation deadline is
     * compared with the command instant exactly as T2 does ({@code LateConfirmCommands.expire}): not paused and at or
     * after {@code expires_at} means T2 owns the outcome ({@link BookingFacts#lateConfirmTimedOut}).
     */
    @Override
    public Map<UUID, BookingFacts> facts(CommandContext command, Collection<UUID> bookingIds) {
        if (bookingIds.isEmpty()) {
            return Map.of();
        }
        List<Row> rows = command.jdbc().sql("""
                        SELECT id, status, source, customer_id, operating_session_id IS NOT NULL AS inside_session,
                               late_confirm_pause_started_at IS NOT NULL AS late_confirm_paused
                          FROM booking.booking
                         WHERE id IN (:ids)""")
                .param("ids", bookingIds)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class),
                        BookingStatus.valueOf(rs.getString("status")), BookingSource.valueOf(rs.getString("source")),
                        rs.getObject("customer_id", UUID.class), rs.getBoolean("inside_session"),
                        rs.getBoolean("late_confirm_paused")))
                .list();
        List<UUID> awaiting = rows.stream().filter(row -> row.status() == BookingStatus.AWAITING_CUSTOMER_CONFIRMATION)
                .map(Row::id).toList();
        Map<UUID, Instant> deadlines = awaiting.isEmpty() ? Map.of() : windows.openDeadlines(command, awaiting);
        Instant now = command.commandNow();
        return rows.stream().map(row -> {
            Instant deadline = deadlines.get(row.id());
            boolean timedOut = row.status() == BookingStatus.AWAITING_CUSTOMER_CONFIRMATION && !row.paused()
                    && deadline != null && !now.isBefore(deadline);
            return new BookingFacts(row.id(), row.status(), row.source(), row.customerId(), row.insideSession(),
                    timedOut);
        }).collect(Collectors.toMap(BookingFacts::bookingId, Function.identity()));
    }

    @Override
    public void cancel(CommandContext command, UUID venueId, UUID venueAccountId, List<UUID> bookingIds) {
        List<UUID> ordered = HoursChangeCancellation.ordered(bookingIds);
        if (ordered.isEmpty()) {
            return;
        }
        cancellation.cancel(command, venueId, venueAccountId, ordered, facts(command, ordered));
    }

    private record Row(UUID id, BookingStatus status, BookingSource source, UUID customerId, boolean insideSession,
                       boolean paused) {
    }
}
