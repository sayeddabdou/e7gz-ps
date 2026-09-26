/**
 * Loyalty module — Append-only points and wallet ledgers, referrals, the booking fee snapshot and refund classification.
 * <p>Spec: arch 03 §16 (module 14). Lane: Platform (Sayed).
 *
 * <p>Owns tables:
 * <ul>
 *   <li>{@code loyalty.points_account}</li>
 *   <li>{@code loyalty.points_transaction}</li>
 *   <li>{@code loyalty.wallet_account}</li>
 *   <li>{@code loyalty.wallet_transaction}</li>
 *   <li>{@code loyalty.referral}</li>
 *   <li>{@code loyalty.reward_application}</li>
 *   <li>{@code loyalty.booking_fee_receipt}</li>
 *   <li>{@code loyalty.booking_fee_refund}</li>
 * </ul>
 *
 * <p>Filled by:
 * <ul>
 *   <li>E7-66 Loyalty ledgers and fee treatment</li>
 *   <li>E7-67 Referrals</li>
 *   <li>web/ stubs: E7-22, E7-39, E7-54, E7-65, E7-77, E7-88, E7-98 (Sx-P0 contract stubs)</li>
 * </ul>
 */
package ps.e7gz.loyalty;
