package ps.e7gz.trust.api;

import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * The Tournament side of a Verified Player revocation (Spec v5 sections 8.9 and 37.8, FR-TRN-20): in the revoking
 * command, the creator's PENDING_VENUE_APPROVAL and WAITING_FOR_PLAYERS Player-hosted Tournaments become CANCELLED with
 * {@code CREATOR_VERIFIED_STATUS_REVOKED}; CONFIRMED and ACTIVE remain. Trust records the cascade once per
 * {@code transition_id} ({@code VP_TOURNAMENT_CASCADE:{transitionId}}) and calls this once for it.
 *
 * <p>Implemented by the Tournament module (E7-94), which depends on Trust. Until then no Player-hosted Tournament can
 * exist (no command creates one), and a revocation that finds one is refused rather than left half-done - the database
 * refuses it at commit too ({@code tournament.assert_verified_player_tournament_cascade}).
 */
public interface VerifiedPlayerTournamentCascade {

    void cancelPendingHostedTournaments(CommandContext command, UUID creatorUserId, UUID transitionId);
}
