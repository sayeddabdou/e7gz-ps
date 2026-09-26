/**
 * Late Confirmation module — Revival of VENUE_RESPONSE_EXPIRED bookings under the same Booking ID: temporary holds, windows, warnings and responses.
 * <p>Spec: arch 03 §12 (module 10). Lane: Engine (Amr).
 *
 * <p>Owns tables:
 * <ul>
 *   <li>{@code booking.late_confirmation}</li>
 * </ul>
 *
 * <p>Filled by:
 * <ul>
 *   <li>E7-58 Late Confirm (O3)</li>
 *   <li>web/ stubs: E7-22, E7-39, E7-54, E7-65, E7-77, E7-88, E7-98 (Sx-P0 contract stubs)</li>
 * </ul>
 */
package ps.e7gz.booking.lateconfirm;
