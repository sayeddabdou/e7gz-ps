package ps.e7gz.geography.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.geo.Coordinates;
import ps.e7gz.common.validation.TextFields;
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

    @Override
    public boolean isActive(UUID areaId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM geo.area WHERE id = :id AND is_active)").param("id", areaId)
                .query(Boolean.class).single();
    }

    @Override
    public Optional<String> name(UUID areaId) {
        return jdbc.sql("SELECT name FROM geo.area WHERE id = :id").param("id", areaId).query(String.class).optional();
    }

    public Area create(UUID adminId, String name, String nameAr, Coordinates centroid) {
        return runner.run(new AreaCommands.Create(adminId, CorrelationId.require(), name.strip(),
                TextFields.optional(nameAr, "nameAr"), centroid));
    }

    /** What an area edit changes; null means unchanged, the remove flags clear the optional values. */
    public record AreaChanges(String name, String nameAr, boolean removeNameAr, Coordinates centroid,
                              boolean removeCentroid) {
    }

    public Area update(UUID adminId, UUID areaId, AreaChanges changes) {
        if (changes.removeNameAr() && changes.nameAr() != null) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "removeNameAr");
        }
        if (changes.removeCentroid() && changes.centroid() != null) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "removeCentroid");
        }
        return runner.run(new AreaCommands.Update(adminId, CorrelationId.require(), areaId,
                TextFields.optional(changes.name(), "name"), TextFields.optional(changes.nameAr(), "nameAr"),
                changes.removeNameAr(), changes.centroid(), changes.removeCentroid()));
    }

    public Area setActive(UUID adminId, UUID areaId, boolean active) {
        return runner.run(new AreaCommands.SetActive(adminId, CorrelationId.require(), areaId, active, audit));
    }
}
