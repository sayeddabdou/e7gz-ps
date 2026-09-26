/**
 * Availability module — The one canonical isFree predicate (FR-AVL-01), business date, the Nearest Availability projection and discovery.
 * <p>Spec: arch 03 §9 (module 7). Lane: Engine (Amr).
 *
 * <p>Owns tables:
 * <ul>
 *   <li>{@code inventory.venue_activity_availability}</li>
 * </ul>
 *
 * <p>Filled by:
 * <ul>
 *   <li>E7-30 isFree: the eight ordered checks</li>
 *   <li>E7-31 Business date</li>
 *   <li>E7-33 Nearest Availability projection</li>
 *   <li>E7-34 Discovery API</li>
 *   <li>web/ stubs: E7-22, E7-39, E7-54, E7-65, E7-77, E7-88, E7-98 (Sx-P0 contract stubs)</li>
 * </ul>
 */
package ps.e7gz.inventory.availability;
