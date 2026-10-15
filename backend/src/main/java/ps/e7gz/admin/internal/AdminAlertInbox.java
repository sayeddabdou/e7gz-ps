package ps.e7gz.admin.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.persistence.DataSourceConfig;

/**
 * The Platform Admin's alert inbox (NFR-29; E7-56, PO decisions 15 Oct 2026). Reads on the admin connection; the one
 * change an alert ever gets is its acknowledgement through {@code admin.acknowledge_alert}, which writes the
 * {@code ADMIN_ALERT_ACKNOWLEDGED} audit row itself.
 */
@Component
public class AdminAlertInbox {

    static final int DEFAULT_PAGE_SIZE = 50;

    public record Alert(UUID id, String kind, String severity, String subjectType, UUID subjectId,
                        Map<String, Object> payload, UUID correlationId, Instant createdAt, Instant acknowledgedAt,
                        UUID acknowledgedByAdminId) {
    }

    public record Page(List<Alert> items, String nextCursor) {
    }

    private static final String COLUMNS = """
            a.id, a.kind, a.severity, a.subject_type, a.subject_id, a.payload::text AS payload, a.correlation_id,
            a.created_at, a.acknowledged_at, a.acknowledged_by_admin_id""";

    private final JdbcClient jdbc;
    private final CommandRunner runner;
    private final ObjectMapper json;

    AdminAlertInbox(@Qualifier(DataSourceConfig.ADMIN_DATA_SOURCE) DataSource adminDataSource, CommandRunner runner,
                    ObjectMapper json) {
        this.jdbc = JdbcClient.create(adminDataSource);
        this.runner = runner;
        this.json = json;
    }

    /**
     * Newest first, then by id. The cursor is the opaque id of the last alert of the previous page (arch 09 section
     * 1.2); an alert never changes its creation time, so acknowledging one cannot move another between pages. A
     * malformed cursor is {@code VALIDATION_FAILED} on {@code cursor}.
     */
    public Page list(boolean acknowledged, String kind, String severity, String cursor, Integer limit) {
        int size = limit == null ? DEFAULT_PAGE_SIZE : limit;
        UUID after = decodeCursor(cursor);
        List<Alert> rows = jdbc.sql("SELECT " + COLUMNS + """

                          FROM admin.admin_alert a
                         WHERE (a.acknowledged_at IS NOT NULL) = :acknowledged
                           AND (CAST(:kind AS text) IS NULL OR a.kind = :kind)
                           AND (CAST(:severity AS text) IS NULL OR a.severity = :severity)
                           AND (CAST(:after AS uuid) IS NULL
                                OR (a.created_at, a.id) < (SELECT c.created_at, c.id FROM admin.admin_alert c
                                                            WHERE c.id = :after))
                         ORDER BY a.created_at DESC, a.id DESC
                         LIMIT :limit""")
                .param("acknowledged", acknowledged)
                .param("kind", blankToNull(kind))
                .param("severity", blankToNull(severity))
                .param("after", after)
                .param("limit", size + 1)
                .query(alertRow())
                .list();
        boolean more = rows.size() > size;
        List<Alert> page = more ? rows.subList(0, size) : rows;
        return new Page(page, more ? encodeCursor(page.getLast().id()) : null);
    }

    /**
     * One acknowledgement per alert. The same admin acknowledging again gets the alert back unchanged (the database's
     * own rule); another admin is {@code ADMIN_ALERT_ALREADY_ACKNOWLEDGED}; an unknown id is {@code NOT_FOUND}.
     */
    public Alert acknowledge(UUID adminId, UUID alertId, String reason) {
        return runner.run(new Acknowledge(adminId, CorrelationId.require(), alertId, reason, alertRow()));
    }

    /** Decided under the row lock the database function also takes, so two admins at once get one winner. */
    private record Acknowledge(UUID adminId, UUID correlationId, UUID alertId, String reason, RowMapper<Alert> rows)
            implements Command<Alert> {

        Acknowledge {
            Objects.requireNonNull(adminId, "adminId");
            Objects.requireNonNull(alertId, "alertId");
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;           // no Venue in scope (ADR-022)
        }

        @Override
        public CommandScope scope() {
            return CommandScope.admin(adminId, correlationId, LockSet.empty());
        }

        @Override
        public Alert execute(CommandContext context) {
            UUID acknowledgedBy = context.jdbc().sql("""
                            SELECT acknowledged_by_admin_id FROM admin.admin_alert WHERE id = :id FOR UPDATE""")
                    .param("id", alertId)
                    .query((rs, row) -> new Holder(rs.getObject(1, UUID.class)))
                    .optional()
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND))
                    .adminId();
            if (acknowledgedBy != null && !acknowledgedBy.equals(adminId)) {
                throw new ApiException(ApiErrorCode.ADMIN_ALERT_ALREADY_ACKNOWLEDGED);
            }
            context.jdbc().sql("SELECT admin.acknowledge_alert(:id, :reason)")
                    .param("id", alertId)
                    .param("reason", reason == null ? null : reason.strip())
                    .query().singleRow();
            return context.jdbc().sql("SELECT " + COLUMNS + " FROM admin.admin_alert a WHERE a.id = :id")
                    .param("id", alertId)
                    .query(rows).single();
        }

        private record Holder(UUID adminId) {
        }
    }

    private RowMapper<Alert> alertRow() {
        return (rs, row) -> new Alert(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("severity"),
                rs.getString("subject_type"), rs.getObject("subject_id", UUID.class), payload(rs.getString("payload")),
                rs.getObject("correlation_id", UUID.class), instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("acknowledged_at", OffsetDateTime.class)),
                rs.getObject("acknowledged_by_admin_id", UUID.class));
    }

    private Map<String, Object> payload(String text) {
        if (text == null) {
            return null;
        }
        try {
            return json.readValue(text, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("admin_alert.payload is not a JSON object", e);
        }
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static String encodeCursor(UUID id) {
        ByteBuffer bytes = ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
    }

    /** Null or blank is the first page; anything else that is not a cursor is VALIDATION_FAILED on {@code cursor}. */
    static UUID decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            if (bytes.length != 16) {
                throw new IllegalArgumentException("cursor length");
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "cursor");
        }
    }
}
