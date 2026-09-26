/**
 * Resource module — Rooms and tables: base state, operational restriction, console type, activity compatibility, resource selling blocks and state-change guards.
 * <p>Spec: arch 03 §6 (module 4). Lane: Platform (Sayed).
 *
 * <p>Owns tables:
 * <ul>
 *   <li>{@code resource.resource}</li>
 *   <li>{@code resource.resource_selling_block}</li>
 *   <li>{@code resource.resource_state_change}</li>
 * </ul>
 *
 * <p>Filled by:
 * <ul>
 *   <li>E7-26 Resources, activities and state changes</li>
 *   <li>web/ stubs: E7-22, E7-39, E7-54, E7-65, E7-77, E7-88, E7-98 (Sx-P0 contract stubs)</li>
 * </ul>
 */
package ps.e7gz.resource;
