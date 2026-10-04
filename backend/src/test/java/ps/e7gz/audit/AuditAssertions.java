package ps.e7gz.audit;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import ps.e7gz.audit.api.AuditAction;

/**
 * Test helper for feature integration tests: "this command wrote exactly one audit row for this action".
 *
 * <pre>{@code
 * AuditRow row = AuditAssertions.assertExactlyOneAuditRow(appDataSource, MyAuditAction.X, subjectId, correlationId);
 * assertThat(row.reason()).isEqualTo("...");
 * }</pre>
 * Rows are matched on action, the action's subject type, subject id and - when given - correlation id. Zero rows and
 * more than one row both fail.
 */
public final class AuditAssertions {

    private AuditAssertions() {
    }

    /** One {@code audit.audit_event} row; {@code payload} is the stored JSON text. */
    public record AuditRow(UUID id, Instant occurredAt, String actorType, UUID actorId, String action,
                           String subjectType, UUID subjectId, String reason, Boolean ownerPasswordUsed,
                           String ownerPasswordOutcome, UUID correlationId, String payload, String previousState,
                           String newState, UUID contextVenueId) {
    }

    /**
     * @param correlationId the expected correlation id, or {@code null} to match any
     * @return the single matching row
     */
    public static AuditRow assertExactlyOneAuditRow(DataSource dataSource, AuditAction action, UUID subjectId,
                                                    UUID correlationId) {
        List<AuditRow> rows = auditRows(dataSource, action, subjectId, correlationId);
        if (rows.size() != 1) {
            throw new AssertionError("Expected exactly one audit row for " + action.name() + " on " + action.subjectType()
                    + " " + subjectId + (correlationId == null ? "" : " with correlation " + correlationId)
                    + ", found " + rows.size());
        }
        return rows.get(0);
    }

    /** Every matching row, oldest first. */
    public static List<AuditRow> auditRows(DataSource dataSource, AuditAction action, UUID subjectId,
                                           UUID correlationId) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(subjectId, "subjectId");
        List<Object> args = new ArrayList<>(List.of(action.name(), action.subjectType(), subjectId));
        String sql = """
                SELECT id, occurred_at, actor_type, actor_id, action, subject_type, subject_id, reason,
                       owner_password_used, owner_password_outcome, correlation_id, payload::text AS payload,
                       previous_state, new_state, context_venue_id
                  FROM audit.audit_event
                 WHERE action = ? AND subject_type = ? AND subject_id = ?
                """;
        if (correlationId != null) {
            sql += " AND correlation_id = ?";
            args.add(correlationId);
        }
        return new JdbcTemplate(dataSource).query(sql + " ORDER BY occurred_at, id", (rs, n) -> new AuditRow(
                rs.getObject("id", UUID.class),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getString("actor_type"),
                rs.getObject("actor_id", UUID.class),
                rs.getString("action"),
                rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class),
                rs.getString("reason"),
                (Boolean) rs.getObject("owner_password_used"),
                rs.getString("owner_password_outcome"),
                rs.getObject("correlation_id", UUID.class),
                rs.getString("payload"),
                rs.getString("previous_state"),
                rs.getString("new_state"),
                rs.getObject("context_venue_id", UUID.class)), args.toArray());
    }
}
