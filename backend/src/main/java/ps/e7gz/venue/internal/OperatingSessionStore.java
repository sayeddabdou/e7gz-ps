package ps.e7gz.venue.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.codes.OpeningDayMode;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.venue.internal.SessionGenerator.PlannedSession;
import ps.e7gz.venue.internal.SessionPlan.ExistingSession;

/**
 * The opening-hours tables and the materialized Operating Sessions (ADR-009), always on a command's transaction with
 * the venue locked. Session rows are written with ordinary DML; {@code venue.guard_operating_session} refuses any
 * change to a referenced one, and {@code ex_session_no_overlap} / {@code uq_operating_session_day} stay the authority
 * on overlap.
 */
final class OperatingSessionStore {

    private static final Logger log = LoggerFactory.getLogger(OperatingSessionStore.class);

    /**
     * SQL condition on {@code venue.operating_session s}: the session is one the venue's current hours still offer (Amr's
     * E7-113 decision 2). A referenced session the hours no longer generate is kept as a historical snapshot (FR-VEN-22)
     * with the generation version it had; it is superseded when that version is older than the template's and it had
     * not started when the template last changed ({@code updated_at}, the change's command instant) - a session that had
     * started then stays in place and in use, as a change applies to sessions still to come. Every other future session
     * is regenerated or restamped to the current version ({@link SessionPlan}). Readers that decide whether a new
     * booking, a Late Confirmation or an availability answer fits the hours use it; readers of an existing booking's
     * history (its business date) do not.
     */
    static final String EFFECTIVE = """
            (NOT EXISTS (SELECT 1 FROM venue.venue_opening_template t
                          WHERE t.id = s.opening_template_id
                            AND s.generated_from_version < t.version
                            AND lower(s.session_period) > t.updated_at))""";

    private OperatingSessionStore() {
    }

    /** The venue's current template, if the hours were ever saved. */
    record Template(UUID id, UUID venueId, OpeningHours hours, long version) {
    }

    /** The venue's time zone ({@code venue.venue.timezone}, Africa/Cairo). */
    static ZoneId zoneOf(JdbcClient jdbc, UUID venueId) {
        return ZoneId.of(jdbc.sql("SELECT timezone FROM venue.venue WHERE id = :venue").param("venue", venueId)
                .query(String.class).single());
    }

    static Optional<Template> template(JdbcClient jdbc, UUID venueId) {
        record Row(UUID id, LocalTime open, LocalTime close, boolean fullDay, long version) {
        }
        Optional<Row> found = jdbc.sql("""
                        SELECT id, default_local_open, default_local_close, default_full_day, version
                          FROM venue.venue_opening_template WHERE venue_id = :venue""")
                .param("venue", venueId)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class),
                        rs.getObject("default_local_open", LocalTime.class),
                        rs.getObject("default_local_close", LocalTime.class), rs.getBoolean("default_full_day"),
                        rs.getLong("version")))
                .optional();
        return found.map(row -> {
            List<OpeningHours.Day> days = jdbc.sql("""
                            SELECT weekday, mode, custom_local_open, custom_local_close
                              FROM venue.venue_opening_day WHERE template_id = :template ORDER BY weekday""")
                    .param("template", row.id())
                    .query((rs, n) -> new OpeningHours.Day(rs.getInt("weekday"),
                            OpeningDayMode.valueOf(rs.getString("mode")),
                            rs.getObject("custom_local_open", LocalTime.class),
                            rs.getObject("custom_local_close", LocalTime.class)))
                    .list();
            return new Template(row.id(), venueId,
                    OpeningHours.stored(row.open(), row.close(), row.fullDay(), days), row.version());
        });
    }

    /**
     * Saves new hours: the template row (created on the first save, otherwise updated to the new version, which is one
     * more on every change) and the seven weekday rows.
     */
    static Template save(CommandContext command, Template template, boolean first) {
        JdbcClient jdbc = command.jdbc();
        OffsetDateTime now = OffsetDateTime.ofInstant(command.commandNow(), ZoneOffset.UTC);
        UUID id = template.id();
        UUID venueId = template.venueId();
        OpeningHours hours = template.hours();
        long version = template.version();
        if (first) {
            jdbc.sql("""
                            INSERT INTO venue.venue_opening_template (id, venue_id, default_local_open,
                                                                      default_local_close, default_full_day, version,
                                                                      updated_at)
                            VALUES (:id, :venue, :open, :close, :fullDay, :version, :now)""")
                    .param("id", id).param("venue", venueId).param("open", hours.defaultOpen())
                    .param("close", hours.defaultClose()).param("fullDay", hours.defaultFullDay())
                    .param("version", version).param("now", now).update();
        } else {
            jdbc.sql("""
                            UPDATE venue.venue_opening_template
                               SET default_local_open = :open, default_local_close = :close,
                                   default_full_day = :fullDay, version = :version, updated_at = :now
                             WHERE id = :id""")
                    .param("id", id).param("open", hours.defaultOpen()).param("close", hours.defaultClose())
                    .param("fullDay", hours.defaultFullDay()).param("version", version).param("now", now).update();
            jdbc.sql("DELETE FROM venue.venue_opening_day WHERE template_id = :id").param("id", id).update();
        }
        for (OpeningHours.Day day : hours.dayList()) {
            jdbc.sql("""
                            INSERT INTO venue.venue_opening_day (id, template_id, weekday, mode, custom_local_open,
                                                                 custom_local_close)
                            VALUES (:id, :template, :weekday, :mode, :open, :close)""")
                    .param("id", UUID.randomUUID()).param("template", id).param("weekday", day.weekday())
                    .param("mode", day.mode().name()).param("open", day.customOpen())
                    .param("close", day.customClose()).update();
        }
        return template;
    }

    /** What regenerating the venue's sessions from {@code template} would do now, for {@code horizonDays} days. */
    static SessionPlan plan(CommandContext command, Template template, ZoneId zone, int horizonDays) {
        Instant now = command.commandNow();
        LocalDate today = LocalDate.ofInstant(now, zone);
        List<PlannedSession> desired = SessionGenerator.generate(template.hours(), zone, today,
                today.plusDays(horizonDays));
        // "Referenced" is the same test venue.guard_operating_session applies before it refuses a change.
        List<ExistingSession> existing = command.jdbc().sql("""
                        SELECT s.id, s.operating_day, s.local_start, s.local_end, lower(s.session_period) AS starts_at,
                               upper(s.session_period) AS ends_at, s.timezone, s.opening_template_id,
                               s.generated_from_version,
                               EXISTS (SELECT 1 FROM booking.booking b WHERE b.operating_session_id = s.id)
                               OR EXISTS (SELECT 1 FROM tournament.tournament t WHERE t.operating_session_id = s.id)
                                   AS referenced
                          FROM venue.operating_session s
                         WHERE s.venue_id = :venue AND s.operating_day >= :from
                         ORDER BY s.operating_day""")
                .param("venue", template.venueId()).param("from", today.minusDays(1))
                .query(OperatingSessionStore::existing).list();
        return SessionPlan.of(existing, desired, template.id(), template.version(), zone.getId(), now);
    }

    /** Applies a plan: deletes first, so a replacement never meets the row it replaces. */
    static void apply(CommandContext command, Template template, ZoneId zone, SessionPlan plan) {
        JdbcClient jdbc = command.jdbc();
        OffsetDateTime now = OffsetDateTime.ofInstant(command.commandNow(), ZoneOffset.UTC);
        if (!plan.delete().isEmpty()) {
            jdbc.sql("DELETE FROM venue.operating_session WHERE id IN (:ids)").param("ids", plan.delete()).update();
        }
        if (!plan.restamp().isEmpty()) {
            jdbc.sql("""
                            UPDATE venue.operating_session SET generated_from_version = :version, generated_at = :now
                             WHERE id IN (:ids)""")
                    .param("version", template.version()).param("now", now).param("ids", plan.restamp()).update();
        }
        for (PlannedSession session : plan.insert()) {
            jdbc.sql("""
                            INSERT INTO venue.operating_session (id, venue_id, opening_template_id, operating_day,
                                                                 local_start, local_end, session_period, timezone,
                                                                 generated_from_version, generated_at)
                            VALUES (:id, :venue, :template, :day, :localStart, :localEnd,
                                    tstzrange(:start, :end, '[)'), :timezone, :version, :now)""")
                    .param("id", UUID.randomUUID()).param("venue", template.venueId())
                    .param("template", template.id()).param("day", session.operatingDay())
                    .param("localStart", session.localStart()).param("localEnd", session.localEnd())
                    .param("start", OffsetDateTime.ofInstant(session.start(), ZoneOffset.UTC))
                    .param("end", OffsetDateTime.ofInstant(session.end(), ZoneOffset.UTC))
                    .param("timezone", zone.getId()).param("version", template.version()).param("now", now)
                    .update();
        }
        if (!plan.pinnedDays().isEmpty() || !plan.skippedDays().isEmpty()) {
            log.info("Operating sessions of venue {} not regenerated: referenced days kept {}, overlapping days {}",
                    template.venueId(), plan.pinnedDays(), plan.skippedDays());
        }
    }

    private static ExistingSession existing(ResultSet rs, int row) throws SQLException {
        return new ExistingSession(rs.getObject("id", UUID.class), rs.getObject("operating_day", LocalDate.class),
                rs.getObject("local_start", LocalDateTime.class), rs.getObject("local_end", LocalDateTime.class),
                rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                rs.getObject("ends_at", OffsetDateTime.class).toInstant(), rs.getString("timezone"),
                rs.getObject("opening_template_id", UUID.class), rs.getLong("generated_from_version"),
                rs.getBoolean("referenced"));
    }
}
