/**
 * Attendance module — Check-in, Mark Present — Late, no-show; attendance result derivation and correction; Platform Attendance Protection.
 * <p>Spec: arch 03 §13 (module 11). Lane: Engine (Amr).
 *
 * <p>Owns tables:
 * <ul>
 *   <li>{@code attendance.attendance_record}</li>
 *   <li>{@code iam.venue_account_restriction_interval}</li>
 *   <li>{@code attendance.attendance_protection_candidate}</li>
 * </ul>
 *
 * <p>Filled by:
 * <ul>
 *   <li>E7-59 Check-in, late present, no-show (O13)</li>
 *   <li>E7-61 Completion and attendance defaults (O12)</li>
 *   <li>E7-78 Attendance protection (Sayed)</li>
 *   <li>web/ stubs: E7-22, E7-39, E7-54, E7-65, E7-77, E7-88, E7-98 (Sx-P0 contract stubs)</li>
 * </ul>
 */
package ps.e7gz.attendance;
