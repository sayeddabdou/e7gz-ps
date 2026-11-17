package ps.e7gz.tournament.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.TournamentHostType;
import ps.e7gz.common.codes.TournamentStatus;

/**
 * A Tournament that is not terminal yet ({@code PENDING_VENUE_APPROVAL}, {@code WAITING_FOR_PLAYERS},
 * {@code CONFIRMED} or {@code ACTIVE}), as a cascade sees it (E7-73, roadmap section 7 dependency note).
 *
 * @param resourceIds the Resources still assigned to it ({@code removed_at IS NULL}), in id order
 */
public record LiveTournament(UUID id, UUID venueId, TournamentHostType hostType, TournamentStatus status,
                             Instant startAt, Instant endAt, List<UUID> resourceIds) {

    public LiveTournament {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(hostType, "hostType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(endAt, "endAt");
        resourceIds = List.copyOf(resourceIds);
    }
}
