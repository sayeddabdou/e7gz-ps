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
 * NFR-26: a terminal booking carries its reason. This check backs up {@code ck_booking_terminal_reason} and runs
 * exactly the arch 11 section 4 query: {@code REJECTED} and {@code CANCELLED} only, because {@code NO_SHOW} carries
 * the {@code NO_SHOW_RECORDED} event, not a reason. It reads {@code booking.booking} (Engine lane, like this module)
 * on the sweep's read-only snapshot and writes nothing.
 */
@Component
class TerminalBookingReasonCheck implements InvariantCheck {

    static final String INVARIANT = "NFR_26_TERMINAL_BOOKING_WITHOUT_REASON";

    static final String TERMINAL_WITHOUT_REASON = """
            SELECT id, status
              FROM booking.booking
             WHERE status IN ('REJECTED', 'CANCELLED')
               AND terminal_reason IS NULL
             ORDER BY id
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
        return snapshot.sql(TERMINAL_WITHOUT_REASON).query(this::violation).list();
    }

    private InvariantViolation violation(ResultSet rs, int row) throws SQLException {
        return new InvariantViolation(INVARIANT, severity(), "BOOKING", rs.getObject("id", UUID.class),
                Map.of("status", rs.getString("status")));
    }
}
