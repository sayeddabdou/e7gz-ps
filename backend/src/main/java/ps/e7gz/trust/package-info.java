/**
 * Trust module — The immutable trust event stream and deterministic replay; no-show counter, penalty stage, bans and Verified Player.
 * <p>Spec: arch 03 §15 (module 13). Lane: Engine (Amr).
 *
 * <p>Owns tables:
 * <ul>
 *   <li>{@code trust.trust_event}</li>
 *   <li>{@code trust.trust_projection}</li>
 *   <li>{@code trust.trust_projection_checkpoint}</li>
 *   <li>{@code trust.ban_qualification}</li>
 *   <li>{@code trust.ban_qualification_consumption}</li>
 *   <li>{@code trust.ban_instance}</li>
 *   <li>{@code trust.ban_cascade_booking_target}</li>
 *   <li>{@code trust.ban_cascade_reservation_target}</li>
 *   <li>{@code trust.cascade_execution}</li>
 *   <li>{@code trust.verified_player_transition}</li>
 * </ul>
 *
 * <p>Filled by:
 * <ul>
 *   <li>E7-69 Trust events and replay</li>
 *   <li>E7-70 Reconciler and attendance corrections</li>
 *   <li>E7-71 Bans and the ban cascade (O14)</li>
 *   <li>E7-72 Verified Player</li>
 *   <li>E7-66 read-only api ports AccountStandings and VerifiedPlayers, for the admin user pages (PO decision D4)</li>
 *   <li>web/ stubs: E7-22, E7-39, E7-54, E7-65, E7-77, E7-88, E7-98 (Sx-P0 contract stubs)</li>
 * </ul>
 */
package ps.e7gz.trust;
