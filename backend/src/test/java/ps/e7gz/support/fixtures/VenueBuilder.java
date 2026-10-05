package ps.e7gz.support.fixtures;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.geo.Coordinates;

/**
 * A {@code venue.venue} with its activities: ONBOARDING (as the Platform Admin creates it) offering PlayStation in a new
 * active area, in Cairo, unless told otherwise. The status is written as is: no transition is replayed.
 */
public final class VenueBuilder {

    private UUID areaId;
    private String name;
    private String address = "12 Test St";
    private MarketplaceStatus status = MarketplaceStatus.ONBOARDING;
    private Set<Activity> activities = EnumSet.of(Activity.PLAYSTATION);
    private Coordinates location = new Coordinates(new BigDecimal("30.0444"), new BigDecimal("31.2357"));
    private String phone = "+20223456789";

    VenueBuilder() {
    }

    public VenueBuilder inArea(UUID areaId) {
        this.areaId = areaId;
        return this;
    }

    public VenueBuilder inArea(Fixtures.Area area) {
        return inArea(area.id());
    }

    public VenueBuilder named(String name) {
        this.name = name;
        return this;
    }

    public VenueBuilder address(String address) {
        this.address = address;
        return this;
    }

    public VenueBuilder status(MarketplaceStatus status) {
        this.status = status;
        return this;
    }

    public VenueBuilder active() {
        return status(MarketplaceStatus.ACTIVE);
    }

    public VenueBuilder inactive() {
        return status(MarketplaceStatus.INACTIVE);
    }

    public VenueBuilder suspended() {
        return status(MarketplaceStatus.SUSPENDED);
    }

    /** The venue's activities; at least one (FR-ADM-02). */
    public VenueBuilder offering(Activity first, Activity... more) {
        this.activities = EnumSet.of(first, more);
        return this;
    }

    public VenueBuilder at(String lat, String lng) {
        this.location = new Coordinates(new BigDecimal(lat), new BigDecimal(lng));
        return this;
    }

    /** E.164, as the venue services store it. */
    public VenueBuilder phone(String phoneE164) {
        this.phone = phoneE164;
        return this;
    }

    public Fixtures.Venue create() {
        return create(FixtureDb.owner());
    }

    public Fixtures.Venue create(JdbcClient db) {
        UUID area = areaId != null ? areaId : new AreaBuilder().create(db).id();
        UUID id = FixtureDb.id();
        String venueName = name != null ? name : "Venue " + FixtureDb.unique();
        db.sql("""
                        INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
                        VALUES (:id, :area, :name, :address, %s, :status, 'Africa/Cairo', :phone)"""
                        .formatted(Coordinates.SQL_POINT))
                .param("id", id).param("area", area).param("name", venueName).param("address", address)
                .param("lat", location.lat()).param("lng", location.lng()).param("status", status.name())
                .param("phone", phone).update();
        for (Activity activity : activities) {
            offer(db, id, activity);
        }
        return new Fixtures.Venue(id, area, venueName);
    }

    /** Adds {@code activity} to the venue if it is not offered yet. */
    static void offer(JdbcClient db, UUID venueId, Activity activity) {
        db.sql("""
                        INSERT INTO venue.venue_activity (id, venue_id, activity) VALUES (:id, :venue, :activity)
                        ON CONFLICT (venue_id, activity) DO NOTHING""")
                .param("id", FixtureDb.id()).param("venue", venueId).param("activity", activity.name()).update();
    }
}
