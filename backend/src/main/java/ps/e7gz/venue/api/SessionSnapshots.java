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
 * session contains an instant; this read adds what must be copied onto the Booking. Current sessions only (v1.1.36);
 * {@code ex_session_no_overlap} makes the answer unique among them.
 */
public interface SessionSnapshots {

    /**
     * The Venue's current session whose {@code session_period} {@code [start, end)} contains {@code at}; empty when
     * none does. Superseded history (v1.1.36) is never returned: a new booking never snapshots it
     * ({@code booking.validate_booking_insert_snapshot}). Reads on the command's own transaction.
     */
    Optional<Session> containing(CommandContext command, UUID venueId, Instant at);

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
