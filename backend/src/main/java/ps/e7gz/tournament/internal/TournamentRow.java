package ps.e7gz.tournament.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.codes.TournamentHostType;
import ps.e7gz.common.codes.TournamentParticipantStatus;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.codes.TournamentTerminalReason;
import ps.e7gz.common.codes.TournamentVisibility;
import ps.e7gz.common.money.Money;

/**
 * One Tournament as the reads see it (E7-126, {@link TournamentReadStore}). {@code capacityOccupied} is
 * {@code tournament.capacity_occupied_count} at the read's instant (FR-TRN-12, FR-TRN-26). {@code accepted}: it has
 * been WAITING_FOR_PLAYERS at least once - a Venue-hosted one from its creation, a Player-hosted one from the Venue's
 * accept (FR-TRN-07, FR-TRN-10). {@code myStatus}: the viewing Customer's own participation, null when they have none
 * or the viewer is not a Customer. The INELIGIBLE cause is not part of it (arch 09 section 3.4, PCR-001).
 */
public record TournamentRow(UUID id, UUID venueId, TournamentHostType hostType, UUID creatorUserId,
                            Boolean creatorParticipates, TournamentStatus status,
                            TournamentTerminalReason terminalReason, Activity activity, PlayMode playMode,
                            TournamentVisibility visibility, int capacity, int capacityOccupied, Instant startAt,
                            Instant endAt, int durationMinutes, Instant checkpointAt, Instant approvalExpiresAt,
                            Instant createdAt, LocalDate businessDate, String inviteCode, Money venueCost,
                            Money creationFee, Money entryFee, Money prize, boolean accepted,
                            TournamentParticipantStatus myStatus) {
}
