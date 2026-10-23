package ps.e7gz.identity.internal;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.persistence.DataSourceConfig;
import ps.e7gz.loyalty.api.LoyaltyBalances;
import ps.e7gz.trust.api.AccountStandings;
import ps.e7gz.trust.api.VerifiedPlayers;

/**
 * The Platform Admin's user management (FR-ADM-04, arch 09 section 5 "Users"; E7-66, PO decision D4, 17 Oct 2026):
 * find a customer and see their standing before a trust or points action. The account is identity's
 * ({@code iam.user_account}, on the admin connection); the no-show standing and Verified Player come from trust and the
 * balances from loyalty, each through its own read-only api port (arch 03 section 2).
 *
 * <p>The list is newest first by joining time, then by id, with keyset cursor paging (arch 09 section 1.2): a customer
 * who registers meanwhile never shifts or repeats a page. An anonymised account (FR-ACC-10) is listed as deleted, its
 * name, email and phone already gone.
 */
@Component
public class AdminUsers {

    static final int DEFAULT_PAGE_SIZE = 20;

    static final String COLUMNS = """
            u.id, u.full_name, u.username, CASE WHEN u.anonymised_at IS NULL THEN u.phone_e164 END AS phone, u.email,
            u.phone_verified, u.email_verified, u.referral_code, u.anonymised_at IS NOT NULL AS deleted, u.joined_at""";

    static final String PAGE = "SELECT " + COLUMNS + """

              FROM iam.user_account u
             WHERE (CAST(:q AS text) IS NULL OR %s)
               AND (CAST(:afterJoined AS timestamptz) IS NULL
                    OR (u.joined_at, u.id) < (CAST(:afterJoined AS timestamptz), CAST(:afterId AS uuid)))
             ORDER BY u.joined_at DESC, u.id DESC
             LIMIT :limit""".formatted(CustomerSearch.MATCHES);

    /**
     * @param phone E.164; {@code null} for an anonymised account, whose phone is no longer a phone number
     */
    public record Account(UUID userId, String fullName, String username, String phone, String email,
                          boolean phoneVerified, boolean emailVerified, String referralCode, boolean deleted,
                          Instant joinedAt) {
    }

    /** One customer as both admin pages show them. */
    public record User(Account account, AccountStandings.Standing standing,
                       VerifiedPlayers.VerifiedPlayer verifiedPlayer) {
    }

    public record Page(List<User> items, String nextCursor) {
    }

    public record Detail(User user, LoyaltyBalances.Balances balances) {
    }

    private final JdbcClient jdbc;
    private final AccountStandings standings;
    private final VerifiedPlayers verifiedPlayers;
    private final LoyaltyBalances balances;

    AdminUsers(@Qualifier(DataSourceConfig.ADMIN_DATA_SOURCE) DataSource adminDataSource, AccountStandings standings,
               VerifiedPlayers verifiedPlayers, LoyaltyBalances balances) {
        this.jdbc = JdbcClient.create(adminDataSource);
        this.standings = standings;
        this.verifiedPlayers = verifiedPlayers;
        this.balances = balances;
    }

    /** Customers newest first; {@code q} as {@link CustomerSearch}. A malformed cursor is VALIDATION_FAILED. */
    public Page list(String q, String cursor, Integer limit) {
        Position after = decode(cursor);
        Optional<CustomerSearch.Terms> terms = CustomerSearch.of(q);
        int size = limit == null ? DEFAULT_PAGE_SIZE : limit;
        List<Account> rows = jdbc.sql(PAGE)
                .param("q", terms.map(CustomerSearch.Terms::text).orElse(null))
                .param("phone", terms.map(CustomerSearch.Terms::phone).orElse(null))
                .param("afterJoined", after == null ? null : OffsetDateTime.ofInstant(after.joinedAt(), ZoneOffset.UTC))
                .param("afterId", after == null ? null : after.userId())
                .param("limit", size + 1)
                .query((rs, n) -> account(rs))
                .list();
        boolean more = rows.size() > size;
        List<Account> page = more ? rows.subList(0, size) : rows;
        List<User> users = withStanding(page);
        return new Page(users, more ? encode(new Position(page.getLast().joinedAt(), page.getLast().userId())) : null);
    }

    /** One customer with balances and referral code; empty when there is no such customer. */
    public Optional<Detail> detail(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        return jdbc.sql("SELECT " + COLUMNS + " FROM iam.user_account u WHERE u.id = :id")
                .param("id", userId)
                .query((rs, n) -> account(rs))
                .optional()
                .map(account -> new Detail(withStanding(List.of(account)).getFirst(), balances.of(userId)
                        .orElseThrow(() -> new IllegalStateException("Customer without loyalty accounts: " + userId))));
    }

    private List<User> withStanding(List<Account> accounts) {
        List<UUID> ids = accounts.stream().map(Account::userId).toList();
        Map<UUID, AccountStandings.Standing> standing = standings.of(ids);
        Map<UUID, VerifiedPlayers.VerifiedPlayer> players = verifiedPlayers.of(ids);
        return accounts.stream()
                .map(account -> new User(account, standing.get(account.userId()), players.get(account.userId())))
                .toList();
    }

    private static Account account(ResultSet rs) throws SQLException {
        return new Account(rs.getObject("id", UUID.class), rs.getString("full_name"), rs.getString("username"),
                rs.getString("phone"), rs.getString("email"), rs.getBoolean("phone_verified"),
                rs.getBoolean("email_verified"), rs.getString("referral_code"), rs.getBoolean("deleted"),
                rs.getObject("joined_at", OffsetDateTime.class).toInstant());
    }

    // ----------------------------------------------------------------------------------------------------- cursor

    /** One customer's place in the list's {@code (joined_at DESC, id DESC)} order. */
    record Position(Instant joinedAt, UUID userId) {
    }

    static String encode(Position position) {
        String plain = position.joinedAt() + "|" + position.userId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /** Null or blank is the first page; anything else that is not a cursor is VALIDATION_FAILED on {@code cursor}. */
    static Position decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String plain = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int bar = plain.indexOf('|');
            if (bar < 0) {
                throw new IllegalArgumentException("no separator");
            }
            return new Position(Instant.parse(plain.substring(0, bar)), UUID.fromString(plain.substring(bar + 1)));
        } catch (IllegalArgumentException | DateTimeParseException malformed) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "cursor");
        }
    }
}
