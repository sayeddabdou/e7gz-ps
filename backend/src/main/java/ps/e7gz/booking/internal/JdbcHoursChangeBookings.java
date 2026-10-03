package ps.e7gz.booking.internal;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.venue.api.HoursChangeBookings;

/** The Booking facts the opening-hours impact preview reads (E7-25), on the command's own transaction. */
@Component
class JdbcHoursChangeBookings implements HoursChangeBookings {

    @Override
    public Map<UUID, BookingFacts> facts(CommandContext command, Collection<UUID> bookingIds) {
        if (bookingIds.isEmpty()) {
            return Map.of();
        }
        return command.jdbc().sql("""
                        SELECT id, status, source, operating_session_id IS NOT NULL AS inside_session
                          FROM booking.booking
                         WHERE id IN (:ids)""")
                .param("ids", bookingIds)
                .query((rs, n) -> new BookingFacts(rs.getObject("id", UUID.class),
                        BookingStatus.valueOf(rs.getString("status")), BookingSource.valueOf(rs.getString("source")),
                        rs.getBoolean("inside_session")))
                .list().stream()
                .collect(Collectors.toMap(BookingFacts::bookingId, Function.identity()));
    }
}
