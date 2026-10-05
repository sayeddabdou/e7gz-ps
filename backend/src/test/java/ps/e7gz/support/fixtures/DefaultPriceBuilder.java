package ps.e7gz.support.fixtures;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.money.Money;

/**
 * One venue default hourly price ({@code pricing.venue_default_price}): PS5 single player at 50.00 EGP unless told
 * otherwise. Billiards and Ping Pong have one rate, without console and play mode ({@code ck_price_dimensions}). The
 * venue is made to offer the activity if it does not.
 */
public final class DefaultPriceBuilder {

    private UUID venueId;
    private Activity activity = Activity.PLAYSTATION;
    private ConsoleType consoleType = ConsoleType.PS5;
    private PlayMode playMode = PlayMode.SINGLE;
    private Money hourly = Money.of("50.00");

    DefaultPriceBuilder() {
    }

    public DefaultPriceBuilder forVenue(UUID venueId) {
        this.venueId = venueId;
        return this;
    }

    public DefaultPriceBuilder forVenue(Fixtures.Venue venue) {
        return forVenue(venue.id());
    }

    public DefaultPriceBuilder playStation(ConsoleType consoleType, PlayMode playMode) {
        this.activity = Activity.PLAYSTATION;
        this.consoleType = consoleType;
        this.playMode = playMode;
        return this;
    }

    public DefaultPriceBuilder billiards() {
        return single(Activity.BILLIARDS);
    }

    public DefaultPriceBuilder pingPong() {
        return single(Activity.PING_PONG);
    }

    private DefaultPriceBuilder single(Activity rate) {
        this.activity = rate;
        this.consoleType = null;
        this.playMode = null;
        return this;
    }

    /** EGP per hour, e.g. {@code "45.50"}. */
    public DefaultPriceBuilder hourly(String egp) {
        this.hourly = Money.of(egp);
        return this;
    }

    public UUID create() {
        return create(FixtureDb.owner());
    }

    public UUID create(JdbcClient db) {
        UUID venue = venueId != null ? venueId : new VenueBuilder().offering(activity).create(db).id();
        VenueBuilder.offer(db, venue, activity);
        UUID id = FixtureDb.id();
        db.sql("""
                        INSERT INTO pricing.venue_default_price (id, venue_id, activity, console_type, play_mode,
                                                                 hourly_price)
                        VALUES (:id, :venue, :activity, :console, :mode, :price)""")
                .param("id", id).param("venue", venue).param("activity", activity.name())
                .param("console", consoleType == null ? null : consoleType.name())
                .param("mode", playMode == null ? null : playMode.name()).param("price", hourly.amount()).update();
        return id;
    }
}
