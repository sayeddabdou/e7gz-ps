package ps.e7gz.support.fixtures;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One materialized {@code venue.operating_session}, exactly as the test declares it (not the session generator), with
 * the Africa/Cairo wall clock made absolute by {@code java.time}. Without {@link #template} the session uses the venue's
 * opening template, and a venue without one gets a template of the session's own hours (every weekday DEFAULT).
 */
public final class OperatingSessionBuilder {

    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");

    private UUID venueId;
    private UUID templateId;
    private LocalDate operatingDay;
    private LocalDateTime localStart;
    private LocalDateTime localEnd;

    OperatingSessionBuilder() {
    }

    public OperatingSessionBuilder forVenue(UUID venueId) {
        this.venueId = venueId;
        return this;
    }

    public OperatingSessionBuilder forVenue(Fixtures.Venue venue) {
        return forVenue(venue.id());
    }

    public OperatingSessionBuilder template(UUID templateId) {
        this.templateId = templateId;
        return this;
    }

    /**
     * The session of {@code operatingDay} from {@code localStart} to {@code localEnd}, Cairo wall clock, e.g.
     * {@code ("2026-11-05", "2026-11-05T18:00", "2026-11-06T02:00")} for a session that crosses midnight.
     */
    public OperatingSessionBuilder on(String operatingDay, String localStart, String localEnd) {
        this.operatingDay = LocalDate.parse(operatingDay);
        this.localStart = LocalDateTime.parse(localStart);
        this.localEnd = LocalDateTime.parse(localEnd);
        return this;
    }

    public Fixtures.OperatingSession create() {
        return create(FixtureDb.owner());
    }

    public Fixtures.OperatingSession create(JdbcClient db) {
        Objects.requireNonNull(operatingDay, "on(day, start, end) is required");
        UUID venue = venueId != null ? venueId : new VenueBuilder().create(db).id();
        UUID template = templateId != null ? templateId : templateOf(db, venue);
        UUID id = FixtureDb.id();
        db.sql("""
                        INSERT INTO venue.operating_session (id, venue_id, opening_template_id, operating_day,
                                                             local_start, local_end, session_period, timezone,
                                                             generated_from_version, generated_at)
                        VALUES (:id, :venue, :template, :day, :localStart, :localEnd,
                                tstzrange(:start, :end, '[)'), 'Africa/Cairo', 0, now())""")
                .param("id", id).param("venue", venue).param("template", template).param("day", operatingDay)
                .param("localStart", localStart).param("localEnd", localEnd)
                .param("start", localStart.atZone(CAIRO).toOffsetDateTime())
                .param("end", localEnd.atZone(CAIRO).toOffsetDateTime()).update();
        return new Fixtures.OperatingSession(id, venue, template);
    }

    private UUID templateOf(JdbcClient db, UUID venue) {
        return db.sql("SELECT id FROM venue.venue_opening_template WHERE venue_id = :venue").param("venue", venue)
                .query(UUID.class).optional()
                .orElseGet(() -> new OpeningTemplateBuilder().forVenue(venue)
                        .hours(localStart.toLocalTime().toString(), localEnd.toLocalTime().toString()).create(db));
    }
}
