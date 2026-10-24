package ps.e7gz.identity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.notification.api.CustomerPushDevices;

/**
 * Releasing an account that never verified its phone (Sprint 0 review S-3, PO decisions 2 and 16 Oct 2026, E7-57): a
 * new registration - or a verified phone change - that needs its phone or username replaces it. No schema change: the
 * row stays (nothing is hard-deleted) and gets placeholders the way anonymisation does ({@code ck_user_anonymised_pii}):
 * <ul>
 *   <li>the phone becomes {@code 'released:' || id}, never a phone, so {@code uq_user_account_phone_e164} frees it;</li>
 *   <li>the username becomes {@code released_<id>}, unless the account changed its username in the last 30 days
 *       ({@code gd_username_cooldown} would refuse it): then it keeps it, and a registration that needs it is
 *       {@code USERNAME_TAKEN} (PO decision 16 Oct 2026);</li>
 *   <li>full name, email and password hash are emptied - an empty hash never matches, so nobody can log in again;</li>
 *   <li>its open OTP codes are superseded, its refresh tokens and push devices revoked (E7-119).</li>
 * </ul>
 * A verified account is never released: its phone and username stay {@code PHONE_TAKEN} / {@code USERNAME_TAKEN}.
 */
final class UnverifiedAccounts {

    /** The phone placeholder of a released account (as anonymisation writes {@code 'anonymised:' || id}). */
    static final String RELEASED_PHONE_PREFIX = "released:";

    private UnverifiedAccounts() {
    }

    /** Another account holding this phone or (trimmed, case-insensitive) username, verified or not. */
    record Holder(UUID userId, boolean phoneVerified, boolean holdsPhone, boolean holdsUsername) {
    }

    /** Read before the command, to name the Users it must lock; the command reads them again under the locks. */
    static List<Holder> holders(JdbcClient jdbc, String phoneE164, String username) {
        return jdbc.sql(HOLDERS).param("phone", phoneE164).param("username", username == null ? "" : username)
                .param("withUsername", username != null).query(UnverifiedAccounts::holder).list();
    }

    /** {@link #holders}, under the command's locks. */
    static List<Holder> holders(CommandContext ctx, String phoneE164, String username) {
        return holders(ctx.jdbc(), phoneE164, username);
    }

    private static final String HOLDERS = """
            SELECT id, phone_verified, phone_e164 = :phone AS holds_phone,
                   (:withUsername AND lower(btrim(username)) = lower(btrim(:username))) AS holds_username
              FROM iam.user_account
             WHERE anonymised_at IS NULL
               AND (phone_e164 = :phone OR (:withUsername AND lower(btrim(username)) = lower(btrim(:username))))
            """;

    private static Holder holder(ResultSet rs, int row) throws SQLException {
        return new Holder(rs.getObject("id", UUID.class), rs.getBoolean("phone_verified"),
                rs.getBoolean("holds_phone"), rs.getBoolean("holds_username"));
    }

    /**
     * Releases every unverified holder, under the command's locks. A verified holder is left alone: the insert or
     * update that follows meets its unique index and answers {@code PHONE_TAKEN} / {@code USERNAME_TAKEN}.
     *
     * @throws ApiException {@code USERNAME_TAKEN} when an unverified holder needs to give up the username but is inside
     *                      its 30-day username cooldown
     */
    static void releaseUnverified(CommandContext ctx, List<Holder> holders, CustomerPushDevices pushDevices) {
        for (Holder holder : holders) {
            if (holder.phoneVerified()) {
                continue;
            }
            boolean usernameFree = !usernameCooldownActive(ctx, holder.userId());
            if (holder.holdsUsername() && !usernameFree) {
                throw new ApiException(ApiErrorCode.USERNAME_TAKEN, "username");
            }
            release(ctx, holder.userId(), usernameFree, pushDevices);
        }
    }

    private static void release(CommandContext ctx, UUID userId, boolean releaseUsername,
                                CustomerPushDevices pushDevices) {
        ctx.jdbc().sql("""
                        UPDATE iam.user_account
                           SET phone_e164 = :phone, full_name = '', email = '', password_hash = '',
                               username = CASE WHEN :releaseUsername THEN :username ELSE username END
                         WHERE id = :id AND NOT phone_verified AND anonymised_at IS NULL
                        """)
                .param("phone", RELEASED_PHONE_PREFIX + userId)
                .param("releaseUsername", releaseUsername)
                .param("username", "released_" + userId.toString().replace("-", ""))
                .param("id", userId)
                .update();
        ctx.jdbc().sql("""
                        UPDATE iam.otp_challenge SET superseded_at = :now
                         WHERE user_id = :id AND consumed_at IS NULL AND superseded_at IS NULL
                        """)
                .param("now", OtpChallenges.utc(ctx.commandNow()))
                .param("id", userId)
                .update();
        RefreshTokens.revokeAll(ctx, AuthSubjectType.CUSTOMER, userId);
        pushDevices.revokeAll(ctx, userId);                          // PO decision 24 Oct 2026 (E7-119)
    }

    /** {@code gd_username_cooldown}'s own test, on the database clock it uses. */
    static boolean usernameCooldownActive(CommandContext ctx, UUID userId) {
        return Boolean.TRUE.equals(ctx.jdbc().sql("""
                        SELECT coalesce(max(changed_at) + interval '30 days' > now(), false)
                          FROM iam.username_change WHERE user_id = :id""")
                .param("id", userId).query(Boolean.class).single());
    }

    /** When the username may change again (the cooldown's end), or empty when it may change now. */
    static Optional<Instant> usernameChangeAvailableAt(JdbcClient jdbc, UUID userId) {
        return jdbc.sql("""
                        SELECT max(changed_at) + interval '30 days' FROM iam.username_change WHERE user_id = :id
                        HAVING max(changed_at) + interval '30 days' > now()""")
                .param("id", userId).query(OffsetDateTime.class).optional()
                .map(OffsetDateTime::toInstant);
    }
}
