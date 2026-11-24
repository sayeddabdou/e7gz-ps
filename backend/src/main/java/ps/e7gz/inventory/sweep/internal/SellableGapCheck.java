package ps.e7gz.inventory.sweep.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.inventory.sweep.api.InvariantCheck;
import ps.e7gz.inventory.sweep.api.InvariantSeverity;
import ps.e7gz.inventory.sweep.api.InvariantViolation;

/**
 * FR-OPS-21 "no sellable gap" (arch 11 section 4; E7-79): an Emergency still in force - ACTIVE or AWAITING_CONFIRMATION
 * (FR-OPS-18: the expected end never reopens anything) - whose subject has no active selling block, so the unusable
 * room or venue could be sold. PO decision 22 Nov 2026, E7-79 (G1): rooms AND venues - a RESOURCE Emergency needs an
 * active {@code resource.resource_selling_block} on its room and a VENUE Emergency an active
 * {@code venue.venue_selling_block} on its venue; arch 11's query covers the room side only. Any active block on the
 * subject closes the gap, as in arch 11. A block left active after its Emergency ended is not this invariant.
 *
 * <p>The database already refuses the gap when a command writes it ({@code operations.assert_emergency_block_consistency},
 * {@code operations.guard_emergency_selling_block}); this nightly check is the independent backstop. It reads the
 * Operations, Resource and Venue tables on the sweep's read-only snapshot and writes nothing. SEV_1, like every sweep
 * finding (arch 11 section 4).
 */
@Component
class SellableGapCheck implements InvariantCheck {

    static final String INVARIANT = "FR_OPS_21_SELLABLE_GAP";

    static final String GAPS = """
            SELECT e.id, e.scope, e.venue_id, e.resource_id, e.status, e.declared_at
              FROM operations.emergency e
             WHERE e.status IN ('ACTIVE', 'AWAITING_CONFIRMATION')
               AND ((e.scope = 'RESOURCE' AND NOT EXISTS (
                        SELECT 1 FROM resource.resource_selling_block b
                         WHERE b.resource_id = e.resource_id AND b.active))
                 OR (e.scope = 'VENUE' AND NOT EXISTS (
                        SELECT 1 FROM venue.venue_selling_block b
                         WHERE b.venue_id = e.venue_id AND b.active)))
             ORDER BY e.id
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
        return snapshot.sql(GAPS).query(this::violation).list();
    }

    private InvariantViolation violation(ResultSet rs, int row) throws SQLException {
        UUID resource = rs.getObject("resource_id", UUID.class);
        Map<String, String> facts = resource == null
                ? Map.of("scope", rs.getString("scope"), "venueId", rs.getString("venue_id"),
                        "status", rs.getString("status"), "declaredAt", declaredAt(rs))
                : Map.of("scope", rs.getString("scope"), "venueId", rs.getString("venue_id"),
                        "resourceId", resource.toString(), "status", rs.getString("status"),
                        "declaredAt", declaredAt(rs));
        return new InvariantViolation(INVARIANT, severity(), "EMERGENCY", rs.getObject("id", UUID.class), facts);
    }

    private static String declaredAt(ResultSet rs) throws SQLException {
        return rs.getObject("declared_at", OffsetDateTime.class).toInstant().toString();
    }
}
