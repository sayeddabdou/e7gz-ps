package ps.e7gz.inventory.sweep.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.inventory.sweep.api.InvariantCheck;
import ps.e7gz.inventory.sweep.api.InvariantSeverity;
import ps.e7gz.inventory.sweep.api.InvariantViolation;

/**
 * NFR-28: no two active reservation rows of one Resource overlap, except the allowed MAINTENANCE / MAINTENANCE and
 * MAINTENANCE / CLOSURE pairs. This is the occupancy guarantee {@code ex_no_double_booking} and
 * {@code tr_10_resource_reservation_cross_class_guard} enforce (arch 05 section 3.1, A1).
 *
 * <p>PO / architecture decision 3 Oct 2026 (E7-32): rows are compared on the immutable {@code planned_range}, and
 * only while {@code active_flag} is true. The arch 11 section 4 {@code actual_reservation_start} range is not used:
 * {@code actual_reservation_start} is the creation / hold instant (E7-29), so that range would flag valid
 * reservations made close together. Released rows are history and are never compared: they may keep a planned
 * interval that overlaps a later row (no-show resell, EC-AVAI-004).
 */
@Component
class ActiveReservationOverlapCheck implements InvariantCheck {

    static final String INVARIANT = "NFR_28_ACTIVE_RESERVATION_OVERLAP";

    /**
     * Every pair once ({@code a.id < b.id}). Only the two allowed pairs are excluded. Every other pair is a violation,
     * including Booking, Late Confirm hold or Tournament hold with Maintenance or Closure, and CLOSURE / CLOSURE.
     */
    static final String OVERLAPPING_ACTIVE_PAIRS = """
            SELECT a.resource_id, a.id AS first_id, a.reservation_type AS first_type,
                   b.id AS second_id, b.reservation_type AS second_type
              FROM inventory.resource_reservation a
              JOIN inventory.resource_reservation b
                ON b.resource_id = a.resource_id
               AND a.id < b.id
             WHERE a.active_flag
               AND b.active_flag
               AND a.planned_range && b.planned_range
               AND NOT (   (a.reservation_type = 'MAINTENANCE' AND b.reservation_type IN ('MAINTENANCE', 'CLOSURE'))
                        OR (b.reservation_type = 'MAINTENANCE' AND a.reservation_type IN ('MAINTENANCE', 'CLOSURE')))
             ORDER BY a.resource_id, a.id, b.id
            """;

    @Override
    public String invariant() {
        return INVARIANT;
    }

    @Override
    public InvariantSeverity severity() {
        return InvariantSeverity.SEV_1;
    }

    @Override
    public List<InvariantViolation> detect(JdbcClient snapshot) {
        return snapshot.sql(OVERLAPPING_ACTIVE_PAIRS).query(this::violation).list();
    }

    private InvariantViolation violation(ResultSet rs, int row) throws SQLException {
        return new InvariantViolation(INVARIANT, severity(), "RESOURCE", rs.getObject("resource_id", UUID.class),
                Map.of("firstReservationId", rs.getString("first_id"),
                        "firstReservationType", rs.getString("first_type"),
                        "secondReservationId", rs.getString("second_id"),
                        "secondReservationType", rs.getString("second_type")));
    }
}
