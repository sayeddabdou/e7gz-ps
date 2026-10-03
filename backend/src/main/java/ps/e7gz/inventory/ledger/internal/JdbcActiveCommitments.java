package ps.e7gz.inventory.ledger.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.ReservationType;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.inventory.ledger.api.ActiveCommitments;
import ps.e7gz.inventory.ledger.api.ReservationOwner;

/**
 * Explicit SQL on the command's own transaction. The venue of a row is its Resource's venue: the one read of
 * {@code resource.resource}, which has no published lookup of a venue's Resources yet.
 */
@Component
class JdbcActiveCommitments implements ActiveCommitments {

    static final String STARTING_AFTER = """
            SELECT rr.id, rr.resource_id, rr.reservation_type, rr.booking_id, rr.tournament_id,
                   rr.planned_start, rr.planned_end
              FROM inventory.resource_reservation rr
              JOIN resource.resource r ON r.id = rr.resource_id
             WHERE r.venue_id = :venueId
               AND rr.active_flag
               AND rr.reservation_type IN ('BOOKING', 'LATE_CONFIRM_HOLD', 'TOURNAMENT_APPROVAL_HOLD', 'TOURNAMENT_HOLD')
               AND rr.planned_start > :after
             ORDER BY rr.planned_start, rr.id
            """;

    @Override
    public List<Commitment> startingAfter(CommandContext command, UUID venueId, Instant after) {
        return command.jdbc().sql(STARTING_AFTER)
                .param("venueId", venueId)
                .param("after", OffsetDateTime.ofInstant(after, ZoneOffset.UTC))
                .query(JdbcActiveCommitments::map)
                .list();
    }

    private static Commitment map(ResultSet rs, int row) throws SQLException {
        ReservationType type = ReservationType.valueOf(rs.getString("reservation_type"));
        UUID bookingId = rs.getObject("booking_id", UUID.class);
        UUID tournamentId = rs.getObject("tournament_id", UUID.class);
        ReservationOwner owner = switch (type) {
            case BOOKING -> ReservationOwner.booking(bookingId);
            case LATE_CONFIRM_HOLD -> ReservationOwner.lateConfirmHold(bookingId);
            case TOURNAMENT_APPROVAL_HOLD -> ReservationOwner.tournamentApprovalHold(tournamentId);
            case TOURNAMENT_HOLD -> ReservationOwner.tournamentHold(tournamentId);
            case MAINTENANCE, CLOSURE -> throw new IllegalStateException("not a commitment row: " + type);
        };
        return new Commitment(rs.getObject("id", UUID.class), rs.getObject("resource_id", UUID.class), owner,
                rs.getObject("planned_start", OffsetDateTime.class).toInstant(),
                rs.getObject("planned_end", OffsetDateTime.class).toInstant());
    }
}
