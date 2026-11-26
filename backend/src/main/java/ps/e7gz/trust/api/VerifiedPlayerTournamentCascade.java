package ps.e7gz.trust.api;

import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * The Tournament side of a Verified Player revocation (Spec v5 sections 8.9 and 37.8, FR-TRN-20): in the revoking
 * command, the creator's PENDING_VENUE_APPROVAL and WAITING_FOR_PLAYERS Player-hosted Tournaments become CANCELLED with
 * {@code CREATOR_VERIFIED_STATUS_REVOKED}; CONFIRMED and ACTIVE remain. Trust records the cascade once per
 * {@code transition_id} ({@code VP_TOURNAMENT_CASCADE:{transitionId}}) and calls this once for it.
 *
 * <p>Implemented by the Tournament module (E7-94), which depends on Trust. The database proves the result at commit
 * ({@code tournament.assert_verified_player_tournament_cascade}). Calling it again for the same transition changes
 * nothing. The command must name the Creator in its lock set, so that {@code begin_command} locks the Creator's
 * Tournaments.
 */
public interface VerifiedPlayerTournamentCascade {

    void cancelPendingHostedTournaments(CommandContext command, UUID creatorUserId, UUID transitionId);
}
