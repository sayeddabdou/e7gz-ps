/**
 * Reservation Ledger module — The single table of exclusive resource occupancy (I1) and its release history.
 * <p>Spec: arch 03 §8 (module 6). Lane: Engine (Amr).
 *
 * <p>Owns tables:
 * <ul>
 *   <li>{@code inventory.resource_reservation}</li>
 * </ul>
 *
 * <p>Filled by:
 * <ul>
 *   <li>E7-29 Reservation ledger</li>
 *   <li>web/ stubs: E7-22, E7-39, E7-54, E7-65, E7-77, E7-88, E7-98 (Sx-P0 contract stubs)</li>
 * </ul>
 */
package ps.e7gz.inventory.ledger;
