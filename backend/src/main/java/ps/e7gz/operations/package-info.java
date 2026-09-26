/**
 * Operations module — Maintenance windows, venue closures, emergencies and their cascades, owner review, and booking timer pause/resume.
 * <p>Spec: arch 03 §10 (module 8). Lane: Engine (Amr).
 *
 * <p>Owns tables:
 * <ul>
 *   <li>{@code operations.maintenance_window}</li>
 *   <li>{@code operations.venue_closure}</li>
 *   <li>{@code operations.emergency}</li>
 *   <li>{@code operations.booking_timer_pause_history}</li>
 *   <li>{@code operations.owner_review}</li>
 *   <li>{@code operations.operational_interruption_record}</li>
 * </ul>
 *
 * <p>Filled by:
 * <ul>
 *   <li>E7-81 Maintenance and closures</li>
 *   <li>E7-82 Emergencies and cascades (O7/O8)</li>
 *   <li>E7-83 Timer pause/resume and feasibility</li>
 *   <li>E7-84 Owner review cascade (O9)</li>
 *   <li>web/ stubs: E7-22, E7-39, E7-54, E7-65, E7-77, E7-88, E7-98 (Sx-P0 contract stubs)</li>
 * </ul>
 */
package ps.e7gz.operations;
