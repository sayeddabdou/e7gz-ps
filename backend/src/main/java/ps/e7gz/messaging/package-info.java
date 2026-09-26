/**
 * Messaging (technical) module — Transactional outbox + dispatcher and write idempotency. No Product rule (arch 08 §4, ADR-015).
 * <p>Spec: arch 03 §17.7 (module 21). Lane: Platform (Sayed).
 *
 * <p>Owns tables:
 * <ul>
 *   <li>{@code technical.outbox_event}</li>
 *   <li>{@code technical.idempotency_record}</li>
 * </ul>
 *
 * <p>Filled by:
 * <ul>
 *   <li>E7-41 Transactional outbox and dispatcher</li>
 *   <li>E7-42 Idempotency on writes</li>
 *   <li>web/ stubs: E7-22, E7-39, E7-54, E7-65, E7-77, E7-88, E7-98 (Sx-P0 contract stubs)</li>
 * </ul>
 */
package ps.e7gz.messaging;
