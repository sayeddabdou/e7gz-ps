package ps.e7gz.resource.internal;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.OperationalRestriction;
import ps.e7gz.common.codes.ResourceBaseState;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;

/** Rooms and tables used by Platform Admin onboarding (FR-ADM-02, FR-VEN-13, FR-VEN-41). */
@Service
public class Resources {

    private static final String SELECT = """
            SELECT id, venue_id, activity, name, console_type, base_state, operational_restriction
              FROM resource.resource""";

    private final CommandRunner runner;
    private final JdbcClient jdbc;

    public Resources(CommandRunner runner, DataSource appDataSource) {
        this.runner = runner;
        this.jdbc = JdbcClient.create(appDataSource);
    }

    public record Resource(UUID id, UUID venueId, Activity activity, String name, ConsoleType consoleType,
                           ResourceBaseState baseState, OperationalRestriction operationalRestriction) {
    }

    public List<Resource> list(UUID venueId) {
        requireVenue(venueId);
        return jdbc.sql(SELECT + " WHERE venue_id = :venue ORDER BY activity, name, id")
                .param("venue", venueId).query(Resources::map).list();
    }

    public Resource create(UUID adminId, UUID venueId, Activity activity, ConsoleType consoleType) {
        requireVenue(venueId);
        validateConsole(activity, consoleType);
        return runner.run(new ResourceCommands.AdminCreate(adminId, CorrelationId.require(), venueId, activity,
                consoleType));
    }

    private void requireVenue(UUID venueId) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM venue.venue WHERE id = :id)")
                .param("id", venueId).query(Boolean.class).single();
        if (!exists) {
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        }
    }

    private static void validateConsole(Activity activity, ConsoleType consoleType) {
        boolean valid = activity == Activity.PLAYSTATION ? consoleType != null : consoleType == null;
        if (!valid) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "consoleType");
        }
    }

    static Resource read(JdbcClient jdbc, UUID resourceId) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", resourceId).query(Resources::map).optional()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }

    private static Resource map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String console = rs.getString("console_type");
        return new Resource(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class),
                Activity.valueOf(rs.getString("activity")), rs.getString("name"),
                console == null ? null : ConsoleType.valueOf(console),
                ResourceBaseState.valueOf(rs.getString("base_state")),
                OperationalRestriction.valueOf(rs.getString("operational_restriction")));
    }
}
