package ps.e7gz.venue.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.venue.api.SessionSnapshots;

/** {@code venue.operating_session} by containment, with its snapshot columns, on the command's transaction. */
@Component
class JdbcSessionSnapshots implements SessionSnapshots {

    /**
     * {@code session_period} is written {@code '[)'}: the start is inside, the end is not. {@link #CONTAINING} only
     * offers a session the current hours still offer ({@link OperatingSessionStore#EFFECTIVE}): a new Online booking
     * or Late Confirmation never fits a superseded historical one.
     */
    static final String ANY_CONTAINING = """
            SELECT s.id, s.operating_day, lower(s.session_period) AS start_at, upper(s.session_period) AS end_at,
                   s.local_start, s.local_end, s.timezone
              FROM venue.operating_session s
             WHERE s.venue_id = :venueId
               AND s.session_period @> CAST(:at AS timestamptz)
            """;

    static final String CONTAINING = ANY_CONTAINING + "   AND " + OperatingSessionStore.EFFECTIVE;

    @Override
    public Optional<Session> containing(CommandContext command, UUID venueId, Instant at) {
        return read(CONTAINING, command, venueId, at);
    }

    @Override
    public Optional<Session> anyContaining(CommandContext command, UUID venueId, Instant at) {
        return read(ANY_CONTAINING, command, venueId, at);
    }

    private static Optional<Session> read(String sql, CommandContext command, UUID venueId, Instant at) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(at, "at");
        return command.jdbc().sql(sql)
                .param("venueId", venueId)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query((rs, n) -> new Session(rs.getObject("id", UUID.class),
                        rs.getObject("operating_day", LocalDate.class),
                        rs.getObject("start_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("end_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("local_start", LocalDateTime.class),
                        rs.getObject("local_end", LocalDateTime.class),
                        rs.getString("timezone")))
                .optional();
    }
}
