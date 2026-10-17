package ps.e7gz.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.aResource;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;
import static ps.e7gz.support.fixtures.Fixtures.aVenueAccount;
import static ps.e7gz.support.fixtures.Fixtures.anOperatingSession;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * v1.1.37 (E7-113): the database side of a superseded Operating Session, written as the owner with every trigger on.
 * A referenced session is superseded once and never changes again; an unreferenced one is never superseded; the day
 * then takes exactly one current session beside it, which may overlap the superseded history but no other current
 * session; a new booking never snapshots superseded history, and a Manual walk-in inside the old window only is out of
 * hours (FR-MAN-03).
 */
class OperatingSessionSupersessionDbTest extends IntegrationTest {

    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));
    private static final String DAY = "2031-05-06";

    private Fixtures.Venue venue;
    private Fixtures.Resource room;
    private Fixtures.OperatingSession old;

    @BeforeEach
    void aVenueWithOneSession() {
        venue = aVenue().active().create();
        aVenueAccount().forVenue(venue).create();                        // a booking needs an ENABLED account
        room = aResource().at(venue).playStation(ConsoleType.PS5).create();
        old = anOperatingSession().forVenue(venue).on(DAY, DAY + "T12:00", DAY + "T23:00").create();
    }

    @Test
    void anUnreferencedSessionIsNeverSuperseded() {
        assertThatThrownBy(() -> supersede(old.id()))
                .hasMessageContaining("Only a referenced Operating Session is superseded");
        assertThat(supersededAt(old.id())).isNull();
    }

    @Test
    void aReferencedSessionIsSupersededOnceAndNeverChangesAgain() {
        manualIn(old.id(), "T14:00", "T15:00", "12:00", "23:00");
        supersede(old.id());

        assertThat(supersededAt(old.id())).isNotNull();
        assertThatThrownBy(() -> OWNER.sql("UPDATE venue.operating_session SET superseded_at = NULL WHERE id = :id")
                .param("id", old.id()).update()).hasMessageContaining("history and never changes");
        assertThatThrownBy(() -> OWNER.sql("""
                        UPDATE venue.operating_session SET generated_from_version = generated_from_version + 1
                         WHERE id = :id""").param("id", old.id()).update())
                .hasMessageContaining("history and never changes");
        assertThatThrownBy(() -> OWNER.sql("DELETE FROM venue.operating_session WHERE id = :id")
                .param("id", old.id()).update()).hasMessageContaining("cannot be deleted");
        assertThatThrownBy(() -> OWNER.sql("""
                        UPDATE venue.operating_session SET superseded_at = generated_at - interval '1 second'
                         WHERE id = :id""").param("id", old.id()).update())
                .hasMessageContaining("history and never changes");
    }

    @Test
    void theDayTakesOneCurrentSessionBesideItsSupersededHistoryAndNoSecondOne() {
        manualIn(old.id(), "T14:00", "T15:00", "12:00", "23:00");
        supersede(old.id());

        UUID current = session("T16:00", "T23:00");                       // overlaps the superseded row: allowed
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM venue.operating_session
                         WHERE venue_id = :v AND operating_day = DATE '2031-05-06'""")
                .param("v", venue.id()).query(Long.class).single()).isEqualTo(2);

        assertThatThrownBy(() -> session("T23:30", "T23:45"))
                .as("a second current session of the day").hasMessageContaining("uq_operating_session_day");
        assertThatThrownBy(() -> supersede(current)).as("the current session is not referenced")
                .hasMessageContaining("Only a referenced Operating Session is superseded");
        assertThat(supersededAt(current)).isNull();
    }

    @Test
    void aCurrentSessionMayNotOverlapAnotherCurrentOne() {
        UUID nextDayEarly = UUID.randomUUID();
        assertThatThrownBy(() -> OWNER.sql("""
                        INSERT INTO venue.operating_session (id, venue_id, opening_template_id, operating_day,
                                                             local_start, local_end, session_period, timezone,
                                                             generated_from_version, generated_at)
                        VALUES (:id, :venue, :template, DATE '2031-05-07', :start, :end,
                                tstzrange(:startAt, :endAt, '[)'), 'Africa/Cairo', 0, now())""")
                .param("id", nextDayEarly).param("venue", venue.id()).param("template", old.templateId())
                .param("start", LocalDateTime.parse(DAY + "T22:00")).param("end", LocalDateTime.parse(DAY + "T23:30"))
                .param("startAt", at("T22:00").atOffset(java.time.ZoneOffset.UTC))
                .param("endAt", at("T23:30").atOffset(java.time.ZoneOffset.UTC)).update())
                .hasMessageContaining("ex_session_no_overlap");
    }

    @Test
    void aNewBookingNeverSnapshotsSupersededHistoryAndAWalkInInTheOldWindowOnlyIsOutOfHours() {
        manualIn(old.id(), "T14:00", "T15:00", "12:00", "23:00");
        supersede(old.id());
        UUID current = session("T16:00", "T23:00");

        assertThatThrownBy(() -> manualIn(old.id(), "T17:00", "T18:00", "12:00", "23:00"))
                .hasMessageContaining("cannot snapshot a superseded Operating Session");
        UUID walkIn = manualOutOfHours("T13:00", "T14:00");               // old window only: no snapshot
        assertThat(OWNER.sql("SELECT operating_session_id FROM booking.booking WHERE id = :id").param("id", walkIn)
                .query(UUID.class).optional()).isEmpty();
        assertThatThrownBy(() -> manualOutOfHours("T17:00", "T18:00"))
                .as("inside the current session a walk-in still snapshots it")
                .hasMessageContaining("must store the normal session snapshot");
        manualIn(current, "T17:00", "T18:00", "16:00", "23:00");
    }

    // ---------------------------------------------------------------- helpers

    private static java.time.Instant at(String time) {
        return LocalDateTime.parse(DAY + time).atZone(CAIRO).toInstant();
    }

    private static void supersede(UUID id) {
        OWNER.sql("UPDATE venue.operating_session SET superseded_at = now() WHERE id = :id").param("id", id).update();
    }

    private static java.time.OffsetDateTime supersededAt(UUID id) {
        return OWNER.sql("SELECT superseded_at FROM venue.operating_session WHERE id = :id").param("id", id)
                .query(java.time.OffsetDateTime.class).optional().orElse(null);
    }

    private UUID session(String start, String end) {
        UUID id = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO venue.operating_session (id, venue_id, opening_template_id, operating_day,
                                                             local_start, local_end, session_period, timezone,
                                                             generated_from_version, generated_at)
                        VALUES (:id, :venue, :template, DATE '2031-05-06', :start, :end,
                                tstzrange(:startAt, :endAt, '[)'), 'Africa/Cairo', 1, now())""")
                .param("id", id).param("venue", venue.id()).param("template", old.templateId())
                .param("start", LocalDateTime.parse(DAY + start)).param("end", LocalDateTime.parse(DAY + end))
                .param("startAt", at(start).atOffset(java.time.ZoneOffset.UTC))
                .param("endAt", at(end).atOffset(java.time.ZoneOffset.UTC)).update();
        return id;
    }

    private UUID manualIn(UUID session, String start, String end, String sessionStart, String sessionEnd) {
        UUID id = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO booking.booking (id, venue_id, resource_id, customer_id, source, status, activity,
                                                     console_type, play_mode, resource_name_snapshot, start_at, end_at,
                                                     nominal_booked_minutes, hourly_price_snapshot, venue_price_total,
                                                     booking_fee_snapshot, total_amount, business_date, operating_day,
                                                     operating_session_id, session_local_start, session_local_end,
                                                     session_timezone, created_at)
                        VALUES (:id, :venue, :resource, NULL, 'MANUAL', 'CONFIRMED', 'PLAYSTATION', 'PS5', 'SINGLE',
                                :name, :start, :end, 60, 100.00, 100.00, 0.00, 100.00, DATE '2031-05-06',
                                DATE '2031-05-06', :session, :localStart, :localEnd, 'Africa/Cairo', now())""")
                .param("id", id).param("venue", venue.id()).param("resource", room.id()).param("name", name())
                .param("start", at(start).atOffset(java.time.ZoneOffset.UTC))
                .param("end", at(end).atOffset(java.time.ZoneOffset.UTC)).param("session", session)
                .param("localStart", LocalDateTime.parse(DAY + "T" + sessionStart))
                .param("localEnd", LocalDateTime.parse(DAY + "T" + sessionEnd)).update();
        return id;
    }

    private UUID manualOutOfHours(String start, String end) {
        UUID id = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO booking.booking (id, venue_id, resource_id, customer_id, source, status, activity,
                                                     console_type, play_mode, resource_name_snapshot, start_at, end_at,
                                                     nominal_booked_minutes, hourly_price_snapshot, venue_price_total,
                                                     booking_fee_snapshot, total_amount, business_date, session_timezone,
                                                     created_at)
                        VALUES (:id, :venue, :resource, NULL, 'MANUAL', 'CONFIRMED', 'PLAYSTATION', 'PS5', 'SINGLE',
                                :name, :start, :end, 60, 100.00, 100.00, 0.00, 100.00, DATE '2031-05-06',
                                'Africa/Cairo', now())""")
                .param("id", id).param("venue", venue.id()).param("resource", room.id()).param("name", name())
                .param("start", at(start).atOffset(java.time.ZoneOffset.UTC))
                .param("end", at(end).atOffset(java.time.ZoneOffset.UTC)).update();
        return id;
    }

    private String name() {
        return OWNER.sql("SELECT name FROM resource.resource WHERE id = :id").param("id", room.id())
                .query(String.class).single();
    }
}
