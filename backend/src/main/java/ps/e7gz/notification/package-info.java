/**
 * Notifications module — Notification catalogue, in-app list, push/email/SMS channels, device tokens and the venue live channel (SSE + Web Push).
 * <p>Spec: arch 03 §17.2 (module 16). Lane: Platform (Sayed).
 *
 * <p>Owns tables:
 * <ul>
 *   <li>{@code notification.notification}</li>
 *   <li>{@code notification.notification_delivery}</li>
 *   <li>{@code notification.notification_catalogue}</li>
 *   <li>{@code notification.device_token}</li>
 *   <li>{@code notification.venue_push_subscription}</li>
 * </ul>
 *
 * <p>Filled by:
 * <ul>
 *   <li>E7-43 Notifications: catalogue, channels, push and email</li>
 *   <li>E7-44 Venue live channel: SSE + Web Push</li>
 *   <li>E7-55 Booking and attendance notifications</li>
 *   <li>E7-80 Operational notifications</li>
 *   <li>E7-89 Tournament notifications</li>
 *   <li>web/ stubs: E7-22, E7-39, E7-54, E7-65, E7-77, E7-88, E7-98 (Sx-P0 contract stubs)</li>
 * </ul>
 */
package ps.e7gz.notification;
