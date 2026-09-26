/**
 * Platform Administration module — Admin identity, admin-facing orchestration of moderation and remediation (delegating to owning modules), admin alerts and reports.
 * <p>Spec: arch 03 §17.5 (module 19). Lane: Platform (Sayed).
 *
 * <p>Owns tables:
 * <ul>
 *   <li>{@code iam.admin_account}</li>
 *   <li>{@code admin.admin_alert}</li>
 * </ul>
 *
 * <p>Filled by:
 * <ul>
 *   <li>E7-56 Admin alerts</li>
 *   <li>E7-99 Remaining admin modules</li>
 *   <li>E7-104 Reports FR-AUD-11/12 and metrics (Amr)</li>
 *   <li>web/ stubs: E7-22, E7-39, E7-54, E7-65, E7-77, E7-88, E7-98 (Sx-P0 contract stubs)</li>
 * </ul>
 */
package ps.e7gz.admin;
