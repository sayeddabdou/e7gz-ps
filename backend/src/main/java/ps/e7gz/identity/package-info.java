/**
 * Identity module — Customer account lifecycle, OTP, passwords, email verification, auth tokens, username changes and account deletion.
 * <p>Spec: arch 03 §3 (module 1). Lane: Platform (Sayed).
 *
 * <p>Owns tables:
 * <ul>
 *   <li>{@code iam.user_account}</li>
 *   <li>{@code iam.otp_challenge}</li>
 *   <li>{@code iam.auth_refresh_token}</li>
 *   <li>{@code iam.username_change}</li>
 *   <li>{@code iam.account_deletion_request}</li>
 *   <li>{@code iam.account_anonymisation}</li>
 *   <li>{@code iam.user_commitment_guard}</li>
 *   <li>{@code iam.security_rate_limit_counter}</li>
 * </ul>
 *
 * <p>Filled by:
 * <ul>
 *   <li>E7-13 Customer registration and phone OTP</li>
 *   <li>E7-14 Login, JWT and refresh rotation for three audiences</li>
 *   <li>E7-57 Profile and account management</li>
 *   <li>E7-103 Account deletion and O19 (Amr)</li>
 *   <li>web/ stubs: E7-22, E7-39, E7-54, E7-65, E7-77, E7-88, E7-98 (Sx-P0 contract stubs)</li>
 * </ul>
 */
package ps.e7gz.identity;
