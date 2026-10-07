package ps.e7gz.booking.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.inventory.availability.api.ScheduleBookings;

/**
 * {@code booking.booking} by primary key for the Venue Dashboard schedule (E7-112), on the app connection.
 * {@link ScheduleBookings} is declared by Availability and implemented here, so Availability never depends on Booking.
 */
@Component
class JdbcScheduleBookings implements ScheduleBookings {

    private final JdbcClient jdbc;

    JdbcScheduleBookings(DataSource appDataSource) {
        this.jdbc = JdbcClient.create(appDataSource);
    }

    @Override
    public Map<UUID, Facts> facts(Collection<UUID> bookingIds) {
        if (bookingIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Facts> facts = new HashMap<>();
        jdbc.sql("SELECT id, status, source FROM booking.booking WHERE id = ANY(:ids)")
                .param("ids", bookingIds.stream().distinct().toArray(UUID[]::new))
                .query(rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    facts.put(id, new Facts(id, BookingStatus.valueOf(rs.getString("status")),
                            BookingSource.valueOf(rs.getString("source"))));
                });
        return facts;
    }
}
