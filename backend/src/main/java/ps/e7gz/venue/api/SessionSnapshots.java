package ps.e7gz.venue.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: one materialized Operating Session ({@code venue.operating_session}, ADR-009) with every column a Booking
 * snapshots (id, operating day, Venue-local start and end, timezone; {@code tr_20_booking_snapshot_integrity}). Added
 * for online booking creation (E7-46, FR-BOK-05 / FR-BOK-11 condition 15). {@link OperatingSessions} answers which
 * session contains an instant; this read adds what must be copied onto the Booking. {@code ex_session_no_overlap} makes
 * the answer unique.
 */
public interface SessionSnapshots {

    /**
     * The Venue's session whose {@code session_period} {@code [start, end)} contains {@code at}, among those its current
     * hours still offer; empty when none does. A historical session the hours no longer generate is never offered
     * (Amr's E7-113 decision 2). Reads on the command's own transaction.
     */
    Optional<Session> containing(CommandContext command, UUID venueId, Instant at);

    /**
     * Any session row containing {@code at}, a superseded historical one included - exactly what
     * {@code booking.validate_booking_insert_snapshot} requires a Manual Booking to snapshot (a Manual Booking may be
     * made outside published hours, FR-MAN-03, so its permission never depends on a session). Only for that snapshot.
     */
    Optional<Session> anyContaining(CommandContext command, UUID venueId, Instant at);

    /** One session: its absolute {@code [start, end)} and the Venue-local wall clock it was generated from. */
    record Session(UUID id, LocalDate operatingDay, Instant start, Instant end, LocalDateTime localStart,
                   LocalDateTime localEnd, String timezone) {

        public Session {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(operatingDay, "operatingDay");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(localStart, "localStart");
            Objects.requireNonNull(localEnd, "localEnd");
            Objects.requireNonNull(timezone, "timezone");
        }
    }
}
