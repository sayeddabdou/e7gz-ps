package ps.e7gz.venue.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: which materialized Operating Session ({@code venue.operating_session}, ADR-009) of a Venue contains an
 * instant, for the business date (arch 04 section 4.3, {@code FR-AVL-14}). The materialized rows are the authority; the
 * weekly opening-hours template is never consulted. {@code ex_session_no_overlap} makes the answer unique.
 */
public interface OperatingSessions {

    /**
     * The {@code operating_day} of the Venue's session whose {@code session_period} {@code [start, end)} contains
     * {@code at}; empty when none does. Reads on the command's own transaction.
     */
    Optional<LocalDate> operatingDayAt(CommandContext command, UUID venueId, Instant at);

    /** The same read outside any command: one statement on the app connection. */
    Optional<LocalDate> operatingDayAt(UUID venueId, Instant at);
}
