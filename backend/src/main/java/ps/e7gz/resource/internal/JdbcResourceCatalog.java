package ps.e7gz.resource.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.resource.api.ResourceCatalog;

/** {@code resource.resource}: identity, venue, activity and console. */
@Component
class JdbcResourceCatalog implements ResourceCatalog {

    static final String SELECT = "SELECT id, venue_id, activity, console_type FROM resource.resource";

    private final JdbcClient jdbc;

    JdbcResourceCatalog(DataSource appDataSource) {
        this.jdbc = JdbcClient.create(appDataSource);
    }

    @Override
    public Optional<Entry> find(CommandContext command, UUID resourceId) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(resourceId, "resourceId");
        return command.jdbc().sql(SELECT + " WHERE id = :id").param("id", resourceId)
                .query(JdbcResourceCatalog::map).optional();
    }

    @Override
    public List<Entry> ofVenue(UUID venueId) {
        return ofVenue(jdbc, venueId);
    }

    @Override
    public List<Entry> ofVenue(CommandContext command, UUID venueId) {
        Objects.requireNonNull(command, "command");
        return ofVenue(command.jdbc(), venueId);
    }

    @Override
    public List<Shown> customerVisible(UUID venueId, Activity activity) {
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(activity, "activity");
        return jdbc.sql("""
                        SELECT id, name, console_type FROM resource.resource
                         WHERE venue_id = :venue AND activity = :activity AND base_state NOT IN ('DISABLED', 'ARCHIVED')
                         ORDER BY name, id""")
                .param("venue", venueId).param("activity", activity.name())
                .query((rs, n) -> {
                    String console = rs.getString("console_type");
                    return new Shown(rs.getObject("id", UUID.class), rs.getString("name"),
                            console == null ? null : ConsoleType.valueOf(console));
                })
                .list();
    }

    private static List<Entry> ofVenue(JdbcClient jdbc, UUID venueId) {
        Objects.requireNonNull(venueId, "venueId");
        return jdbc.sql(SELECT + " WHERE venue_id = :venue ORDER BY activity, name, id").param("venue", venueId)
                .query(JdbcResourceCatalog::map).list();
    }

    private static Entry map(ResultSet rs, int row) throws SQLException {
        String console = rs.getString("console_type");
        return new Entry(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class),
                Activity.valueOf(rs.getString("activity")), console == null ? null : ConsoleType.valueOf(console));
    }
}
