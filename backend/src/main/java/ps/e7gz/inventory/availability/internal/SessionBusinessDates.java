package ps.e7gz.inventory.availability.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.inventory.availability.api.BusinessDates;
import ps.e7gz.venue.api.OperatingSessions;

/**
 * {@link BusinessDates} over the Venue's materialized Operating Sessions, read through the Venue module's api (the
 * E7-30 owner-API boundary); this class holds no SQL.
 */
@Component
class SessionBusinessDates implements BusinessDates {

    private final OperatingSessions sessions;
    private final Clock clock;

    SessionBusinessDates(OperatingSessions sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    public LocalDate businessDate(CommandContext command, UUID venueId, Instant startAt) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(startAt, "startAt");
        return resolve(sessions.operatingDayAt(command, venueId, startAt), startAt);
    }

    @Override
    public LocalDate currentBusinessDate(UUID venueId) {
        Objects.requireNonNull(venueId, "venueId");
        Instant now = clock.instant();
        return resolve(sessions.operatingDayAt(venueId, now), now);
    }

    /** Rule 1, else rule 2 (arch 04 section 4.3, FR-AVL-14). */
    static LocalDate resolve(Optional<LocalDate> operatingDay, Instant at) {
        return operatingDay.orElseGet(() -> LocalDate.ofInstant(at, FALLBACK_ZONE));
    }
}
