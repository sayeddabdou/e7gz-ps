/**
 * Audit module — Who did what, when and why, for every important action.
 * <p>Spec: arch 03 §17.4 (module 18). Lane: Platform (Sayed) — written by Amr in E7-17.
 *
 * <p>Owns tables:
 * <ul>
 *   <li>{@code audit.audit_event}</li>
 * </ul>
 *
 * <p>Filled by:
 * <ul>
 *   <li>E7-17 Audit writer</li>
 *   <li>web/ stubs: E7-22, E7-39, E7-54, E7-65, E7-77, E7-88, E7-98 (Sx-P0 contract stubs)</li>
 * </ul>
 */
package ps.e7gz.audit;
