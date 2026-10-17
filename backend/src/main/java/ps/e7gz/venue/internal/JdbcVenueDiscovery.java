package ps.e7gz.venue.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.geo.Coordinates;
import ps.e7gz.venue.api.VenueDiscovery;

/**
 * Customer discovery reads of {@code venue.venue} (E7-34). The list is one statement: the ACTIVE, activity and area
 * filters, the order and the keyset boundary are all SQL; PostGIS computes distance and order with the KNN operator
 * {@code <->} on the GiST-indexed geography ({@code ix_venue_location_gist}, ADR-019). No column selected here is the
 * venue phone (FR-DIS-09).
 *
 * <p>Nearest first, the page order is {@code (distance, id)}: the id makes ties deterministic. PostgreSQL uses a GiST
 * index for {@code ORDER BY} only when the whole order is the distance operator, so the statement takes the page in
 * two index-assisted steps and orders only those rows by {@code (distance, id)}:
 * <ol>
 *   <li>{@code knn}: the first {@code :limit} rows by {@code location <-> customer} alone - a KNN index scan;</li>
 *   <li>{@code ties}: every other row at exactly the largest distance of {@code knn}, found through the same index
 *       ({@code ST_DWithin} on the sphere, as {@code <->} measures) - the rows a tie could still put on the page.</li>
 * </ol>
 * Every row nearer than that distance is in {@code knn}, so the first {@code :limit} rows of both by
 * {@code (distance, id)} are exactly the first {@code :limit} of the whole list.
 */
@Component
class JdbcVenueDiscovery implements VenueDiscovery {

    /** The customer position, {@code :lat} / {@code :lng}. */
    static final String CUSTOMER = Coordinates.SQL_POINT;

    /** KNN distance in metres; the same expression orders, bounds the page and is reported. */
    static final String DISTANCE = "(v.location <-> " + CUSTOMER + ")";

    /** ACTIVE venues offering the activity (FR-DIS-08); then {@code %s}: the area and keyset conditions. */
    static final String ELIGIBLE = """
              FROM venue.venue v
             WHERE v.marketplace_status = 'ACTIVE'
               AND EXISTS (SELECT 1 FROM venue.venue_activity a WHERE a.venue_id = v.id AND a.activity = :activity)%s""";

    static final String IN_AREA = "\n   AND v.area_id = :areaId";

    /** Keyset boundary after the cursor venue, nearest-first order: {@code (distance, id)}. */
    static final String AFTER_BY_DISTANCE = "\n   AND (" + DISTANCE + ", v.id) > ((SELECT c.location <-> " + CUSTOMER
            + " FROM venue.venue c WHERE c.id = :after), CAST(:after AS uuid))";

    /** Keyset boundary after the cursor venue, area-only order: {@code (name, id)}. */
    static final String AFTER_BY_NAME =
            "\n   AND (v.name, v.id) > ((SELECT c.name FROM venue.venue c WHERE c.id = :after), CAST(:after AS uuid))";

    static final String NEAREST = """
            WITH knn AS MATERIALIZED (
                SELECT v.id, v.name, v.area_id, %1$s AS distance
            %2$s
                 ORDER BY %1$s
                 LIMIT :limit),
            ties AS (
                SELECT v.id, v.name, v.area_id, %1$s AS distance
            %2$s
                   AND ST_DWithin(v.location, %3$s, (SELECT max(k.distance) FROM knn k) + 1, false)
                   AND %1$s = (SELECT max(k.distance) FROM knn k))
            SELECT p.id, p.name, p.area_id, round(p.distance)::integer AS distance_meters
              FROM (SELECT * FROM knn UNION SELECT * FROM ties) p
             ORDER BY p.distance, p.id
             LIMIT :limit""";

    static final String BY_NAME = """
            SELECT v.id, v.name, v.area_id, NULL::integer AS distance_meters
            %s
             ORDER BY v.name, v.id
             LIMIT :limit""";

    /**
     * Inside the map viewport, edges included. The viewport is a longitude/latitude rectangle, as the map draws it, so
     * the test is planar on the point's coordinates ({@code geometry}), not on the sphere.
     */
    static final String IN_VIEWPORT = """

               AND ST_Intersects(v.location::geometry,
                                 ST_MakeEnvelope(CAST(:minLng AS double precision), CAST(:minLat AS double precision),
                                                 CAST(:maxLng AS double precision), CAST(:maxLat AS double precision),
                                                 4326))""";

    /** {@code %1$s}: the location columns; {@code %2$s}: the distance; {@code %3$s}: eligibility; {@code %4$s}: order. */
    static final String MARKERS = """
            SELECT v.id, v.name, %1$s, %2$s AS distance_meters
            %3$s
             ORDER BY %4$s""";

    static final String IN_AREA_BY_NAME = """
            SELECT v.id, v.name, v.area_id, NULL::integer AS distance_meters
            %s
             ORDER BY v.name, v.id""";

    static final String PROFILE = """
            SELECT v.id, v.name, v.area_id, v.address, v.marketplace_status, %s, %s AS distance_meters,
                   ARRAY(SELECT a.activity FROM venue.venue_activity a WHERE a.venue_id = v.id) AS activities
              FROM venue.venue v
             WHERE v.id = :id""";

    static final String OPEN_STATE = """
            SELECT EXISTS (SELECT 1 FROM venue.operating_session s
                            WHERE s.venue_id = :venue AND s.session_period @> CAST(:at AS timestamptz)
                              AND """ + OperatingSessionStore.EFFECTIVE + """
            ) AS open_now,
                   (SELECT min(lower(s.session_period)) FROM venue.operating_session s
                     WHERE s.venue_id = :venue AND lower(s.session_period) > CAST(:at AS timestamptz)
                       AND """ + OperatingSessionStore.EFFECTIVE + """
            ) AS next_start
            """;

    private final JdbcClient jdbc;

    JdbcVenueDiscovery(DataSource appDataSource) {
        this.jdbc = JdbcClient.create(appDataSource);
    }

    /** The list statement for a search's shape; only fixed fragments are joined, never request text. */
    static String listSql(boolean near, boolean inArea, boolean paged) {
        String conditions = (inArea ? IN_AREA : "") + (paged ? (near ? AFTER_BY_DISTANCE : AFTER_BY_NAME) : "");
        String eligible = ELIGIBLE.formatted(conditions);
        return near ? NEAREST.formatted(DISTANCE, eligible, CUSTOMER) : BY_NAME.formatted(eligible);
    }

    @Override
    public Page search(Search search) {
        boolean near = search.near() != null;
        if (search.after() != null && !exists(search.after())) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "cursor");
        }
        JdbcClient.StatementSpec statement = jdbc.sql(listSql(near, search.areaId() != null, search.after() != null))
                .param("activity", search.activity().name())
                .param("limit", search.limit() + 1);
        if (near) {
            statement = statement.param("lat", search.near().lat()).param("lng", search.near().lng());
        }
        if (search.areaId() != null) {
            statement = statement.param("areaId", search.areaId());
        }
        if (search.after() != null) {
            statement = statement.param("after", search.after());
        }
        List<Listed> rows = statement.query((rs, n) -> new Listed(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getObject("area_id", UUID.class), (Integer) rs.getObject("distance_meters"))).list();
        boolean more = rows.size() > search.limit();
        return new Page(more ? List.copyOf(rows.subList(0, search.limit())) : rows, more);
    }

    @Override
    public List<Marker> markers(MapSearch search) {
        boolean near = search.near() != null;
        String eligible = ELIGIBLE.formatted((search.areaId() != null ? IN_AREA : "") + IN_VIEWPORT);
        String sql = MARKERS.formatted(Coordinates.sqlColumns("v.location", "location"),
                near ? "round" + DISTANCE + "::integer" : "NULL::integer", eligible,
                near ? DISTANCE + ", v.id" : "v.name, v.id");
        Viewport viewport = search.viewport();
        JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("activity", search.activity().name())
                .param("minLng", viewport.minLng()).param("minLat", viewport.minLat())
                .param("maxLng", viewport.maxLng()).param("maxLat", viewport.maxLat());
        if (near) {
            statement = statement.param("lat", search.near().lat()).param("lng", search.near().lng());
        }
        if (search.areaId() != null) {
            statement = statement.param("areaId", search.areaId());
        }
        return statement.query((rs, n) -> new Marker(rs.getObject("id", UUID.class), rs.getString("name"),
                new Coordinates(rs.getBigDecimal("location_lat"), rs.getBigDecimal("location_lng")),
                (Integer) rs.getObject("distance_meters"))).list();
    }

    @Override
    public List<Listed> inArea(UUID areaId, Activity activity) {
        Objects.requireNonNull(areaId, "areaId");
        Objects.requireNonNull(activity, "activity");
        return jdbc.sql(IN_AREA_BY_NAME.formatted(ELIGIBLE.formatted(IN_AREA)))
                .param("activity", activity.name()).param("areaId", areaId)
                .query((rs, n) -> new Listed(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getObject("area_id", UUID.class), null))
                .list();
    }

    @Override
    public Optional<Profile> profile(UUID venueId, Coordinates from) {
        Objects.requireNonNull(venueId, "venueId");
        String sql = PROFILE.formatted(Coordinates.sqlColumns("v.location", "location"),
                from == null ? "NULL::integer" : "round" + DISTANCE + "::integer");
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("id", venueId);
        if (from != null) {
            statement = statement.param("lat", from.lat()).param("lng", from.lng());
        }
        return statement.query(JdbcVenueDiscovery::profile).optional();
    }

    @Override
    public List<OpeningDay> weeklyHours(UUID venueId) {
        Objects.requireNonNull(venueId, "venueId");
        Optional<OperatingSessionStore.Template> template = OperatingSessionStore.template(jdbc, venueId);
        List<OpeningDay> week = new ArrayList<>();
        if (template.isEmpty()) {
            for (int weekday = 0; weekday < OpeningHours.WEEKDAYS; weekday++) {
                week.add(new OpeningDay(weekday, true, null, null));
            }
            return week;
        }
        OpeningHours hours = template.get().hours();
        for (OpeningHours.Day day : hours.dayList()) {
            week.add(switch (day.mode()) {
                case CLOSED -> new OpeningDay(day.weekday(), true, null, null);
                case DEFAULT -> new OpeningDay(day.weekday(), false, hours.defaultOpen(), hours.defaultClose());
                case CUSTOM -> new OpeningDay(day.weekday(), false, day.customOpen(), day.customClose());
            });
        }
        return week;
    }

    @Override
    public OpenState openState(UUID venueId, Instant at) {
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(at, "at");
        return jdbc.sql(OPEN_STATE).param("venue", venueId).param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query((rs, n) -> {
                    boolean open = rs.getBoolean("open_now");
                    OffsetDateTime next = rs.getObject("next_start", OffsetDateTime.class);
                    return new OpenState(open, open || next == null ? null : next.toInstant());
                })
                .single();
    }

    private boolean exists(UUID venueId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM venue.venue WHERE id = :id)").param("id", venueId)
                .query(Boolean.class).single();
    }

    private static Profile profile(ResultSet rs, int row) throws SQLException {
        Array offered = rs.getArray("activities");
        EnumSet<Activity> activities = EnumSet.noneOf(Activity.class);
        Arrays.stream((Object[]) offered.getArray()).forEach(a -> activities.add(Activity.valueOf((String) a)));
        return new Profile(rs.getObject("id", UUID.class), rs.getString("name"), rs.getObject("area_id", UUID.class),
                rs.getString("address"),
                new Coordinates(rs.getBigDecimal("location_lat"), rs.getBigDecimal("location_lng")),
                MarketplaceStatus.valueOf(rs.getString("marketplace_status")), List.copyOf(activities),
                (Integer) rs.getObject("distance_meters"));
    }
}
