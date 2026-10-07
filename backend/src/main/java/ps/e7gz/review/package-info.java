/**
 * Reviews module — Review lifecycle, reporting and moderation states, the post-removal cooldown and venue rating statistics.
 * <p>Spec: arch 03 §17.1 (module 15). Lane: Engine (Amr).
 *
 * <p>Owns tables:
 * <ul>
 *   <li>{@code review.review}</li>
 *   <li>{@code review.review_report}</li>
 *   <li>{@code review.review_moderation_event}</li>
 *   <li>{@code review.review_cooldown}</li>
 *   <li>{@code review.venue_rating_stats}</li>
 * </ul>
 *
 * <p>Filled by:
 * <ul>
 *   <li>E7-102 Reviews, reporting and moderation</li>
 *   <li>E7-34 implements Availability's read-only {@code DiscoveryRatings} port (reads {@code venue_rating_stats})</li>
 *   <li>web/ stubs: E7-22, E7-39, E7-54, E7-65, E7-77, E7-88, E7-98 (Sx-P0 contract stubs)</li>
 * </ul>
 */
package ps.e7gz.review;
