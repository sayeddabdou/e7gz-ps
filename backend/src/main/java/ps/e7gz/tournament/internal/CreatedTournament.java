package ps.e7gz.tournament.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.codes.TournamentHostType;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.codes.TournamentVisibility;
import ps.e7gz.common.money.Money;

/**
 * A Tournament as its creating command wrote it (E7-91): what the request asked for and what the database derived and
 * snapshot. Each route group builds its own response from it (arch 09 section 1.1).
 *
 * @param creatorUserId       the Player Creator; {@code null} when Venue-hosted
 * @param creatorParticipates whether the Player Creator plays (FR-TRN-05); {@code null} when Venue-hosted
 * @param approvalExpiresAt   {@code MIN(createdAt + 10 min, checkpointAt)} as the database set it (FR-TRN-10);
 *                            {@code null} when Venue-hosted
 * @param inviteCode          the private Tournament's code; {@code null} when public
 */
public record CreatedTournament(UUID id, UUID venueId, TournamentHostType hostType, TournamentStatus status,
                                UUID creatorUserId, Boolean creatorParticipates, Activity activity, PlayMode playMode,
                                int capacity, Instant startAt, Instant endAt, int durationMinutes,
                                Instant checkpointAt, Instant approvalExpiresAt, Instant createdAt,
                                TournamentVisibility visibility, String inviteCode, Money venueCostTotal,
                                Money creationFee, Money entryFee, Money prize, LocalDate businessDate,
                                List<Resource> resources) {

    public CreatedTournament {
        resources = List.copyOf(resources);
    }

    /**
     * FR-TRN-07: while the request waits for the Venue its invite code and link are inactive. The code exists (the
     * database requires one for a private Tournament) but is not given out until the Venue accepts (E7-92).
     */
    public boolean inviteCodeActive() {
        return inviteCode != null && status != TournamentStatus.PENDING_VENUE_APPROVAL;
    }

    /**
     * One held Resource with its snapshots (FR-TRN-28).
     *
     * @param consoleType the console snapshot, {@code null} off PlayStation
     */
    public record Resource(UUID resourceId, String consoleType, Money hourlyPrice) {
    }
}
