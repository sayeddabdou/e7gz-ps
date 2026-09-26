/**
 * Pricing module — Venue default prices, room overrides, price resolution and the price snapshot written into every priced record.
 * <p>Spec: arch 03 §7 (module 5). Lane: Platform (Sayed).
 *
 * <p>Owns tables:
 * <ul>
 *   <li>{@code pricing.venue_default_price}</li>
 *   <li>{@code pricing.resource_price_override}</li>
 * </ul>
 *
 * <p>Filled by:
 * <ul>
 *   <li>E7-27 Pricing and rounding</li>
 *   <li>web/ stubs: E7-22, E7-39, E7-54, E7-65, E7-77, E7-88, E7-98 (Sx-P0 contract stubs)</li>
 * </ul>
 */
package ps.e7gz.pricing;
