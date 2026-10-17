package ps.e7gz.venue.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: which materialized Operating Session ({@code venue.operating_session}, ADR-009) of a Venue contains an
 * instant, for the business date (arch 04 section 4.3, {@code FR-AVL-14}), and which sessions are still to come, for
 * Nearest Availability. The materialized rows are the authority; the
 * weekly opening-hours template is never consulted. Only current sessions are read - superseded history (v1.1.36)
 * never is - and {@code ex_session_no_overlap} makes the answer unique among them.
 */
public interface OperatingSessions {

    /**
     * The {@code operating_day} of the Venue's session whose {@code session_period} {@code [start, end)} contains
     * {@code at}; empty when none does. Reads on the command's own transaction.
     */
    Optional<LocalDate> operatingDayAt(CommandContext command, UUID venueId, Instant at);

    /** The same read outside any command: one statement on the app connection. */
    Optional<LocalDate> operatingDayAt(UUID venueId, Instant at);

    /**
     * The Venue's materialized sessions that end after {@code after}, in start order, for Nearest Availability (arch 05
     * section 5.2, ADR-009; E7-33). A session already running at {@code after} is included. Reads on the command's own
     * transaction.
     */
    List<SessionPeriod> endingAfter(CommandContext command, UUID venueId, Instant after);

    /**
     * The Venue's materialized sessions whose {@code operating_day} is in {@code [first, last]}, in start order, for the
     * Dashboard schedule's business day (E7-112, arch 04 section 4.3). {@code uq_operating_session_day} makes it at most
     * one current session per day. One statement on the app connection, outside any command.
     */
    List<SessionPeriod> onOperatingDays(UUID venueId, LocalDate first, LocalDate last);

    /** One session's absolute {@code session_period} {@code [start, end)}. */
    record SessionPeriod(LocalDate operatingDay, Instant start, Instant end) {

        public SessionPeriod {
            Objects.requireNonNull(operatingDay, "operatingDay");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
        }
    }
}
