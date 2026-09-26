/**
 * Platform Configuration module — Product-configurable values (booking fee, points) with append-only, effective-dated versions.
 * <p>Spec: arch 03 §17.3 (module 17). Lane: Platform (Sayed).
 *
 * <p>Owns tables:
 * <ul>
 *   <li>{@code config.configuration_key}</li>
 *   <li>{@code config.configuration_version}</li>
 *   <li>{@code config.system_configuration}</li>
 * </ul>
 *
 * <p>Filled by:
 * <ul>
 *   <li>E7-68 Configuration service (fees and points)</li>
 *   <li>web/ stubs: E7-22, E7-39, E7-54, E7-65, E7-77, E7-88, E7-98 (Sx-P0 contract stubs)</li>
 * </ul>
 */
package ps.e7gz.config;
