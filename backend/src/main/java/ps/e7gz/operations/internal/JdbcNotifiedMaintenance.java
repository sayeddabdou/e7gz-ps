package ps.e7gz.operations.internal;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.notification.api.NotifiedMaintenance;

/** The notification module's read of one Maintenance window (E7-80, "Maintenance Starting Soon"), deleted or not. */
@Component
class JdbcNotifiedMaintenance implements NotifiedMaintenance {

    static final String FIND = """
            SELECT m.id, r.venue_id, m.resource_id, lower(m.period) AS start_at, m.deleted_at IS NOT NULL AS deleted
              FROM operations.maintenance_window m
              JOIN resource.resource r ON r.id = m.resource_id
             WHERE m.id = :id
            """;

    @Override
    public Optional<Window> find(CommandContext command, UUID maintenanceId) {
        return command.jdbc().sql(FIND).param("id", maintenanceId)
                .query((rs, n) -> new Window(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class),
                        rs.getObject("resource_id", UUID.class),
                        rs.getObject("start_at", OffsetDateTime.class).toInstant(), rs.getBoolean("deleted")))
                .optional();
    }
}
