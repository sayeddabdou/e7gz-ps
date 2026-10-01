/**
 * The access JWT and the API security chain for the customer, venue and admin audiences (arch 10 section 2.3). Login,
 * refresh-token rotation and the login rate limit are Identity's ({@code iam.auth_refresh_token},
 * {@code iam.security_rate_limit_counter}); each identity's owning module supplies its {@link
 * ps.e7gz.common.security.ActiveSubjectCheck}.
 * <p>Filled by: E7-14.
 */
package ps.e7gz.common.security;
