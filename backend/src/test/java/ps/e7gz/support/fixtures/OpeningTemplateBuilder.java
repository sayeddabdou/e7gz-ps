package ps.e7gz.support.fixtures;

import java.time.LocalTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A venue's weekly hours ({@code venue.venue_opening_template}), 10:00-12:00 every day unless told otherwise; no
 * {@code venue_opening_day} rows, so every weekday is DEFAULT. Only the template: no session is generated. Note that the
 * nightly horizon extension visits every venue with a template.
 */
public final class OpeningTemplateBuilder {

    private UUID venueId;
    private LocalTime open = LocalTime.of(10, 0);
    private LocalTime close = LocalTime.of(12, 0);

    OpeningTemplateBuilder() {
    }

    public OpeningTemplateBuilder forVenue(UUID venueId) {
        this.venueId = venueId;
        return this;
    }

    public OpeningTemplateBuilder forVenue(Fixtures.Venue venue) {
        return forVenue(venue.id());
    }

    /** Local wall clock; {@code close <= open} crosses midnight, equal times are a full day. */
    public OpeningTemplateBuilder hours(String open, String close) {
        this.open = LocalTime.parse(open);
        this.close = LocalTime.parse(close);
        return this;
    }

    public UUID create() {
        return create(FixtureDb.owner());
    }

    public UUID create(JdbcClient db) {
        UUID venue = venueId != null ? venueId : new VenueBuilder().create(db).id();
        UUID id = FixtureDb.id();
        db.sql("""
                        INSERT INTO venue.venue_opening_template (id, venue_id, default_local_open, default_local_close,
                                                                  default_full_day, updated_at)
                        VALUES (:id, :venue, :open, :close, :fullDay, now())""")
                .param("id", id).param("venue", venue).param("open", open).param("close", close)
                .param("fullDay", open.equals(close)).update();
        return id;
    }
}
