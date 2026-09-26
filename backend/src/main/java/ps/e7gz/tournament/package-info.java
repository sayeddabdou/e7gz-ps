/**
 * Tournament module — The tournament entity (separate from bookings): approval, holds, participants, checkpoint, lifecycle and cost snapshot.
 * <p>Spec: arch 03 §14 (module 12). Lane: Engine (Amr).
 *
 * <p>Owns tables:
 * <ul>
 *   <li>{@code tournament.tournament}</li>
 *   <li>{@code tournament.tournament_resource}</li>
 *   <li>{@code tournament.tournament_participant}</li>
 *   <li>{@code tournament.tournament_history}</li>
 * </ul>
 *
 * <p>Filled by:
 * <ul>
 *   <li>E7-73 Tournament entity (early)</li>
 *   <li>E7-91 Tournament creation and holds (O16 part 1)</li>
 *   <li>E7-92 Approval, rejection, timeout (T7–T9)</li>
 *   <li>E7-93 Join, leave, checkpoint, start, cancel</li>
 *   <li>E7-94 Resource loss, Verified Player cascade, precedence</li>
 *   <li>web/ stubs: E7-22, E7-39, E7-54, E7-65, E7-77, E7-88, E7-98 (Sx-P0 contract stubs)</li>
 * </ul>
 */
package ps.e7gz.tournament;
