package ps.e7gz.geography.internal;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.geo.Coordinates;
import ps.e7gz.geography.api.AreaLookup;

/** Areas for the Platform Admin and for customer discovery (FR-VEN-12, FR-VEN-47; arch 09 sections 3.2 and 5). */
@Service
public class Areas implements AreaLookup {

    private final CommandRunner runner;
    private final AuditWriter audit;
    private final JdbcClient jdbc;

    Areas(CommandRunner runner, AuditWriter audit, DataSource appDataSource) {
        this.runner = runner;
        this.audit = audit;
        this.jdbc = JdbcClient.create(appDataSource);
    }

    /** Every area, active and inactive, by name. */
    public List<Area> all() {
        return jdbc.sql("SELECT " + Area.COLUMNS + " FROM geo.area ORDER BY name, id").query(Area::map).list();
    }

    /** The areas customers may browse: active only (FR-VEN-12). */
    public List<Area> active() {
        return jdbc.sql("SELECT " + Area.COLUMNS + " FROM geo.area WHERE is_active ORDER BY name, id")
                .query(Area::map).list();
    }

    @Override
    public boolean exists(UUID areaId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM geo.area WHERE id = :id)").param("id", areaId)
                .query(Boolean.class).single();
    }

    public Area create(UUID adminId, String name, String nameAr, Coordinates centroid) {
        return runner.run(new AreaCommands.Create(adminId, CorrelationId.require(), name.strip(), blankToNull(nameAr),
                centroid));
    }

    public Area update(UUID adminId, UUID areaId, String name, String nameAr, Coordinates centroid) {
        return runner.run(new AreaCommands.Update(adminId, CorrelationId.require(), areaId, blankToNull(name),
                blankToNull(nameAr), centroid));
    }

    public Area setActive(UUID adminId, UUID areaId, boolean active) {
        return runner.run(new AreaCommands.SetActive(adminId, CorrelationId.require(), areaId, active, audit));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
