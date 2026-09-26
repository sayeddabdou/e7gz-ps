/**
 * Scheduling (technical) module — The scheduled_job table and the lease-based deadline worker. No Product rule (arch 08 §2).
 * <p>Spec: arch 03 §17.6 (module 20). Lane: Platform (Sayed).
 *
 * <p>Owns tables:
 * <ul>
 *   <li>{@code technical.scheduled_job}</li>
 * </ul>
 *
 * <p>Filled by:
 * <ul>
 *   <li>E7-40 Deadline engine (scheduled_job)</li>
 *   <li>web/ stubs: E7-22, E7-39, E7-54, E7-65, E7-77, E7-88, E7-98 (Sx-P0 contract stubs)</li>
 * </ul>
 */
package ps.e7gz.scheduling;
