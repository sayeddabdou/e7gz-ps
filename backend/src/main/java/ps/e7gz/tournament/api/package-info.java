/**
 * Tournament — Published interfaces and DTOs — the only part of this module other modules may use.
 *
 * <p>{@link ps.e7gz.tournament.api.TournamentCascades} (E7-73): find the live Tournaments a Venue or Resource cause
 * reaches and cancel them as a system consequence, releasing their holds - for the Sprint 5 emergency and owner-review
 * cascades (roadmap section 7 dependency note). E7-94 adds the resource-loss handler
 * ({@link ps.e7gz.tournament.api.TournamentCascades#loseResource}, arch 06 section 3.5).
 *
 * <p>Ports of other modules this module implements: {@code trust.api.TournamentParticipantCauses} (E7-93, O14),
 * {@code trust.api.VerifiedPlayerTournamentCascade} (E7-94, O15), {@code identity.api.AccountDeletionTournaments}
 * (E7-94, O19) and {@code venue.api.AccountDisableTournaments} (E7-78).
 */
package ps.e7gz.tournament.api;
