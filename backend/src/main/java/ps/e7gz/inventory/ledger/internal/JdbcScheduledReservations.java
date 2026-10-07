package ps.e7gz.inventory.ledger.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.ReservationType;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.inventory.ledger.api.ScheduledReservations;

/** {@code inventory.resource_reservation} by Resource and planned-range overlap, on the app connection (E7-112). */
@Component
class JdbcScheduledReservations implements ScheduledReservations {

    /** {@code planned_range} is canonical {@code [)}, so {@code &&} is half-open overlap. */
    static final String ACTIVE = """
            SELECT rr.id, rr.resource_id, rr.reservation_type, rr.booking_id, rr.tournament_id,
                   rr.planned_start, rr.planned_end
              FROM inventory.resource_reservation rr
             WHERE rr.resource_id = ANY(:resourceIds)
               AND rr.active_flag
               AND rr.actual_reservation_release_at IS NULL
               AND rr.reservation_type IN ('BOOKING', 'LATE_CONFIRM_HOLD', 'TOURNAMENT_APPROVAL_HOLD', 'TOURNAMENT_HOLD')
               AND rr.planned_range && tstzrange(CAST(:from AS timestamptz), CAST(:to AS timestamptz), '[)')
             ORDER BY rr.resource_id, rr.planned_start, rr.id
            """;

    private final JdbcClient jdbc;

    JdbcScheduledReservations(DataSource appDataSource) {
        this.jdbc = JdbcClient.create(appDataSource);
    }

    @Override
    public List<Row> active(Collection<UUID> resourceIds, Instant from, Instant to) {
        Objects.requireNonNull(resourceIds, "resourceIds");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("to must be after from: [" + from + ", " + to + ")");
        }
        if (resourceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(ACTIVE)
                .param("resourceIds", resourceIds.stream().distinct().toArray(UUID[]::new))
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .query(JdbcScheduledReservations::map)
                .list();
    }

    private static Row map(ResultSet rs, int row) throws SQLException {
        ReservationType type = ReservationType.valueOf(rs.getString("reservation_type"));
        UUID bookingId = rs.getObject("booking_id", UUID.class);
        UUID tournamentId = rs.getObject("tournament_id", UUID.class);
        ReservationOwner owner = switch (type) {
            case BOOKING -> ReservationOwner.booking(bookingId);
            case LATE_CONFIRM_HOLD -> ReservationOwner.lateConfirmHold(bookingId);
            case TOURNAMENT_APPROVAL_HOLD -> ReservationOwner.tournamentApprovalHold(tournamentId);
            case TOURNAMENT_HOLD -> ReservationOwner.tournamentHold(tournamentId);
            case MAINTENANCE, CLOSURE -> throw new IllegalStateException("not a schedule commitment row: " + type);
        };
        return new Row(rs.getObject("id", UUID.class), rs.getObject("resource_id", UUID.class), owner,
                rs.getObject("planned_start", OffsetDateTime.class).toInstant(),
                rs.getObject("planned_end", OffsetDateTime.class).toInstant());
    }
}
