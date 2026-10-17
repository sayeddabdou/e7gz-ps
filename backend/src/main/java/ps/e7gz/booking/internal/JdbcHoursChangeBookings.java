package ps.e7gz.booking.internal;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
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

    JdbcHoursChangeBookings(HoursChangeCancellation cancellation) {
        this.cancellation = cancellation;
    }

    @Override
    public Map<UUID, BookingFacts> facts(CommandContext command, Collection<UUID> bookingIds) {
        if (bookingIds.isEmpty()) {
            return Map.of();
        }
        return command.jdbc().sql("""
                        SELECT id, status, source, customer_id, operating_session_id IS NOT NULL AS inside_session
                          FROM booking.booking
                         WHERE id IN (:ids)""")
                .param("ids", bookingIds)
                .query((rs, n) -> new BookingFacts(rs.getObject("id", UUID.class),
                        BookingStatus.valueOf(rs.getString("status")), BookingSource.valueOf(rs.getString("source")),
                        rs.getObject("customer_id", UUID.class), rs.getBoolean("inside_session")))
                .list().stream()
                .collect(Collectors.toMap(BookingFacts::bookingId, Function.identity()));
    }

    @Override
    public void cancel(CommandContext command, UUID venueId, UUID venueAccountId, List<UUID> bookingIds) {
        List<UUID> ordered = HoursChangeCancellation.ordered(bookingIds);
        if (ordered.isEmpty()) {
            return;
        }
        cancellation.cancel(command, venueId, venueAccountId, ordered, facts(command, ordered));
    }
}
