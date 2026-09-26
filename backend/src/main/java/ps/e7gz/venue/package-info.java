/**
 * Venue module — The venue record and its status domains, the shared venue account and Owner Password, opening hours, operating-session generation and venue-level selling blocks.
 * <p>Spec: arch 03 §5 (module 3). Lane: Platform (Sayed).
 *
 * <p>Owns tables:
 * <ul>
 *   <li>{@code venue.venue}</li>
 *   <li>{@code venue.venue_image}</li>
 *   <li>{@code venue.venue_activity}</li>
 *   <li>{@code iam.venue_account}</li>
 *   <li>{@code iam.venue_owner_secret}</li>
 *   <li>{@code venue.venue_opening_template}</li>
 *   <li>{@code venue.venue_opening_day}</li>
 *   <li>{@code venue.operating_session}</li>
 *   <li>{@code venue.venue_selling_block}</li>
 * </ul>
 *
 * <p>Filled by:
 * <ul>
 *   <li>E7-23 Areas, venues and marketplace status</li>
 *   <li>E7-24 Venue account and Owner Password</li>
 *   <li>E7-25 Opening hours and operating sessions (DST)</li>
 *   <li>E7-78 Venue account disable (O10)</li>
 *   <li>web/ stubs: E7-22, E7-39, E7-54, E7-65, E7-77, E7-88, E7-98 (Sx-P0 contract stubs)</li>
 * </ul>
 */
package ps.e7gz.venue;
