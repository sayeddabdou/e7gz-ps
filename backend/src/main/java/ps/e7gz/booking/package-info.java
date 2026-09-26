/**
 * Booking module — Online and manual booking lifecycle, the 20-condition creation predicate, terminal reasons, append-only history, commitment counting and customer projections.
 * <p>Spec: arch 03 §11 (module 9). Lane: Engine (Amr).
 *
 * <p>Owns tables:
 * <ul>
 *   <li>{@code booking.booking}</li>
 *   <li>{@code booking.booking_history}</li>
 * </ul>
 *
 * <p>Filled by:
 * <ul>
 *   <li>E7-46 Online booking creation (O1)</li>
 *   <li>E7-47 Venue accept / reject / expiry (O2)</li>
 *   <li>E7-48 Walk-in / manual booking (O4)</li>
 *   <li>E7-49 Cancellation, customer buckets, contact visibility</li>
 *   <li>E7-60 Start-time revalidation (O11)</li>
 *   <li>web/ stubs: E7-22, E7-39, E7-54, E7-65, E7-77, E7-88, E7-98 (Sx-P0 contract stubs)</li>
 * </ul>
 */
package ps.e7gz.booking;
