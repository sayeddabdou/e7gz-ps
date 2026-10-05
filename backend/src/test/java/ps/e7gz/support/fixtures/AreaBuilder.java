package ps.e7gz.support.fixtures;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** A {@code geo.area}, active unless {@link #inactive()}. */
public final class AreaBuilder {

    private String name;
    private String nameAr;
    private boolean active = true;

    AreaBuilder() {
    }

    public AreaBuilder named(String name) {
        this.name = name;
        return this;
    }

    public AreaBuilder namedAr(String nameAr) {
        this.nameAr = nameAr;
        return this;
    }

    /** Hidden from discovery (FR-VEN-12); a venue in it may not be ACTIVE or SUSPENDED. */
    public AreaBuilder inactive() {
        this.active = false;
        return this;
    }

    public Fixtures.Area create() {
        return create(FixtureDb.owner());
    }

    public Fixtures.Area create(JdbcClient db) {
        UUID id = FixtureDb.id();
        String areaName = name != null ? name : "Area " + FixtureDb.unique();
        db.sql("""
                        INSERT INTO geo.area (id, name, name_ar, is_active, centroid)
                        VALUES (:id, :name, :nameAr, :active, 'SRID=4326;POINT(31.2357 30.0444)'::geography)""")
                .param("id", id).param("name", areaName).param("nameAr", nameAr).param("active", active).update();
        return new Fixtures.Area(id, areaName);
    }
}
