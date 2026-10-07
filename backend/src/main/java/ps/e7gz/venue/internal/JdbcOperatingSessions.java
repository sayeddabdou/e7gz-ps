package ps.e7gz.venue.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.venue.api.OperatingSessions;

/**
 * {@code venue.operating_session} by containment of an instant in its absolute {@code session_period}, or by the
 * sessions still to end (E7-33).
 */
@Component
class JdbcOperatingSessions implements OperatingSessions {

    /** {@code session_period} is written {@code '[)'}: the start is inside, the end is not. */
    static final String OPERATING_DAY_AT = """
            SELECT s.operating_day
              FROM venue.operating_session s
             WHERE s.venue_id = :venueId
               AND s.session_period @> CAST(:at AS timestamptz)
            """;

    /** {@code upper(session_period)} is the first instant outside the session. */
    static final String ENDING_AFTER = """
            SELECT s.operating_day, lower(s.session_period) AS start_at, upper(s.session_period) AS end_at
              FROM venue.operating_session s
             WHERE s.venue_id = :venueId
               AND upper(s.session_period) > CAST(:after AS timestamptz)
             ORDER BY lower(s.session_period)
            """;

    private final JdbcClient jdbc;

    JdbcOperatingSessions(DataSource appDataSource) {
        this.jdbc = JdbcClient.create(appDataSource);
    }

    @Override
    public Optional<LocalDate> operatingDayAt(CommandContext command, UUID venueId, Instant at) {
        Objects.requireNonNull(command, "command");
        return operatingDayAt(command.jdbc(), venueId, at);
    }

    @Override
    public Optional<LocalDate> operatingDayAt(UUID venueId, Instant at) {
        return operatingDayAt(jdbc, venueId, at);
    }

    @Override
    public List<SessionPeriod> endingAfter(CommandContext command, UUID venueId, Instant after) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(after, "after");
        return command.jdbc().sql(ENDING_AFTER)
                .param("venueId", venueId)
                .param("after", OffsetDateTime.ofInstant(after, ZoneOffset.UTC))
                .query((rs, n) -> new SessionPeriod(rs.getObject("operating_day", LocalDate.class),
                        rs.getObject("start_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("end_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private static Optional<LocalDate> operatingDayAt(JdbcClient jdbc, UUID venueId, Instant at) {
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(at, "at");
        return jdbc.sql(OPERATING_DAY_AT)
                .param("venueId", venueId)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query(LocalDate.class).optional();
    }
}
