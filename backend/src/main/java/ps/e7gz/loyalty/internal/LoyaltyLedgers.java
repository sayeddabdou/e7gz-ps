package ps.e7gz.loyalty.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.PointsTransactionCause;
import ps.e7gz.common.codes.WalletTransactionCause;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.money.Money;
import ps.e7gz.common.persistence.DataSourceConfig;
import ps.e7gz.loyalty.api.AccountHolders;
import ps.e7gz.loyalty.api.AccountHolders.Holder;

/**
 * The points and wallet ledgers as their owners and the Platform Admin read them (arch 09 sections 3.4 and 5,
 * FR-PTS-01, FR-PTS-03 to FR-PTS-05, FR-PTS-10, FR-ADM-09, PCR-004; E7-66). Reads only: the ledgers are written by the
 * {@code loyalty.*} producers alone (ADR-026), and the wallet has no write path at all (FR-PTS-11, PCR-004).
 *
 * <p>Every history is newest first, by {@code ledger_sequence} (the order the producers appended in), and comes with
 * the account's balance, which is never negative for points ({@code ck_points_balance_nonnegative}). A customer reads
 * on the app connection, the Platform Admin on the admin connection.
 */
@Component
public class LoyaltyLedgers {

    /** A cursor page; {@code nextCursor} is null on the last one. */
    public record Page<T>(List<T> items, String nextCursor) {
    }

    /**
     * One points ledger row. {@code amount} is what was applied: a deduction of {@code requestedAmount} below the
     * balance applies {@code MIN(balance, requested)} and records {@code unrecoveredAmount} (FR-PTS-03, FR-PTS-04).
     */
    public record PointsTransaction(UUID transactionId, UUID userId, Instant occurredAt, PointsTransactionCause cause,
                                    int requestedAmount, int amount, int unrecoveredAmount, int balanceAfter,
                                    UUID bookingId, UUID referralId, UUID adminId, String reason) {
    }

    /**
     * One wallet ledger row ({@code ck_wallet_tx_source}): a Booking Fee refund credits {@code amount}, a Booking Fee
     * payment of a later booking spends it ({@code amount} is negative; PCR-009).
     */
    public record WalletTransaction(UUID transactionId, Instant occurredAt, WalletTransactionCause cause, Money amount,
                                    Money balanceAfter, UUID bookingId) {
    }

    public record PointsHistory(int balance, Page<PointsTransaction> page) {
    }

    public record WalletHistory(Money balance, Page<WalletTransaction> page) {
    }

    /** A points account in the admin list: net points from completed bookings and from referrals (FR-ADM-09). */
    public record PointsAccount(Holder holder, int balance, int bookingRewards, int referralRewards) {
    }

    public record WalletAccount(Holder holder, Money balance) {
    }

    public record AdminPointsHistory(PointsAccount account, Page<PointsTransaction> page) {
    }

    public record AdminWalletHistory(WalletAccount account, Page<WalletTransaction> page) {
    }

    static final String POINTS_PAGE = """
            SELECT t.id, t.points_account_id, t.occurred_at, t.cause, t.requested_amount, t.amount,
                   t.unrecovered_reversal_amount, t.balance_after, t.booking_id, t.referral_id, t.admin_id, t.reason
              FROM loyalty.points_transaction t
             WHERE t.points_account_id = :user
               AND (CAST(:after AS uuid) IS NULL
                    OR t.ledger_sequence < (SELECT c.ledger_sequence FROM loyalty.points_transaction c
                                             WHERE c.id = :after AND c.points_account_id = :user))
             ORDER BY t.ledger_sequence DESC
             LIMIT :limit""";

    static final String WALLET_PAGE = """
            SELECT t.id, t.occurred_at, t.cause, t.amount, t.balance_after, t.booking_id
              FROM loyalty.wallet_transaction t
             WHERE t.wallet_account_id = :user
               AND (CAST(:after AS uuid) IS NULL
                    OR t.ledger_sequence < (SELECT c.ledger_sequence FROM loyalty.wallet_transaction c
                                             WHERE c.id = :after AND c.wallet_account_id = :user))
             ORDER BY t.ledger_sequence DESC
             LIMIT :limit""";

    /** The page of accounts first, then the net rewards of those accounts only. */
    static final String POINTS_ACCOUNTS = """
            SELECT p.user_id, p.balance, COALESCE(r.booking_rewards, 0) AS booking_rewards,
                   COALESCE(r.referral_rewards, 0) AS referral_rewards
              FROM (SELECT a.user_id, a.balance
                      FROM loyalty.points_account a
                     WHERE (CAST(:ids AS uuid[]) IS NULL OR a.user_id = ANY(CAST(:ids AS uuid[])))
                       AND (CAST(:afterBalance AS integer) IS NULL
                            OR (a.balance, a.user_id) < (CAST(:afterBalance AS integer), CAST(:afterUser AS uuid)))
                     ORDER BY a.balance DESC, a.user_id DESC
                     LIMIT :limit) p
              LEFT JOIN LATERAL (
                   SELECT sum(t.amount) FILTER (WHERE t.booking_id IS NOT NULL) AS booking_rewards,
                          sum(t.amount) FILTER (WHERE t.referral_id IS NOT NULL) AS referral_rewards
                     FROM loyalty.points_transaction t
                    WHERE t.points_account_id = p.user_id) r ON true
             ORDER BY p.balance DESC, p.user_id DESC""";

    static final String WALLET_ACCOUNTS = """
            SELECT a.user_id, a.balance
              FROM loyalty.wallet_account a
             WHERE (CAST(:ids AS uuid[]) IS NULL OR a.user_id = ANY(CAST(:ids AS uuid[])))
               AND (CAST(:afterBalance AS numeric) IS NULL
                    OR (a.balance, a.user_id) < (CAST(:afterBalance AS numeric), CAST(:afterUser AS uuid)))
             ORDER BY a.balance DESC, a.user_id DESC
             LIMIT :limit""";

    private final JdbcClient app;
    private final JdbcClient admin;
    private final AccountHolders holders;

    LoyaltyLedgers(DataSource appDataSource, @Qualifier(DataSourceConfig.ADMIN_DATA_SOURCE) DataSource adminDataSource,
                   AccountHolders holders) {
        this.app = JdbcClient.create(appDataSource);
        this.admin = JdbcClient.create(adminDataSource);
        this.holders = holders;
    }

    // ------------------------------------------------------------------------------------------------- customer

    /** The customer's own points: balance and history (FR-PTS-01, FR-PTS-05). */
    public PointsHistory points(UUID userId, String cursor, Integer limit) {
        Objects.requireNonNull(userId, "userId");
        return new PointsHistory(pointsBalance(app, userId), pointsPage(app, userId, cursor, limit));
    }

    /** The customer's own wallet: balance and Booking Fee refunds, read-only (PCR-004, FR-PTS-10). */
    public WalletHistory wallet(UUID userId, String cursor, Integer limit) {
        Objects.requireNonNull(userId, "userId");
        return new WalletHistory(walletBalance(app, userId), walletPage(app, userId, cursor, limit));
    }

    // ---------------------------------------------------------------------------------------------- Platform Admin

    /** Points accounts, highest balance first; {@code q} as {@link AccountHolders#search} (FR-ADM-09). */
    public Page<PointsAccount> pointsAccounts(String q, String cursor, Integer limit) {
        LedgerCursor.Position after = LedgerCursor.position(cursor);
        Integer afterBalance = after == null ? null : wholePoints(after.balance());
        UUID[] ids = matching(q);
        if (ids != null && ids.length == 0) {
            return new Page<>(List.of(), null);
        }
        int size = LedgerCursor.size(limit);
        List<AccountRow<Integer>> rows = admin.sql(POINTS_ACCOUNTS)
                .param("ids", ids).param("afterBalance", afterBalance)
                .param("afterUser", after == null ? null : after.userId()).param("limit", size + 1)
                .query((rs, n) -> new AccountRow<>(rs.getObject("user_id", UUID.class), rs.getInt("balance"),
                        rs.getInt("booking_rewards"), rs.getInt("referral_rewards")))
                .list();
        return accounts(rows, size, row -> BigDecimal.valueOf(row.balance()),
                (row, holder) -> new PointsAccount(holder, row.balance(), row.bookingRewards(), row.referralRewards()));
    }

    /** One user's points account and ledger, with requested, applied and unrecovered amounts; empty: no such user. */
    public Optional<AdminPointsHistory> pointsAccount(UUID userId, String cursor, Integer limit) {
        Objects.requireNonNull(userId, "userId");
        return holder(userId).map(holder -> new AdminPointsHistory(rewards(holder, pointsBalance(admin, userId)),
                pointsPage(admin, userId, cursor, limit)));
    }

    /** Wallets, highest balance first; {@code q} as {@link AccountHolders#search} (FR-ADM-09). */
    public Page<WalletAccount> walletAccounts(String q, String cursor, Integer limit) {
        LedgerCursor.Position after = LedgerCursor.position(cursor);
        UUID[] ids = matching(q);
        if (ids != null && ids.length == 0) {
            return new Page<>(List.of(), null);
        }
        int size = LedgerCursor.size(limit);
        List<AccountRow<Money>> rows = admin.sql(WALLET_ACCOUNTS)
                .param("ids", ids).param("afterBalance", after == null ? null : after.balance())
                .param("afterUser", after == null ? null : after.userId()).param("limit", size + 1)
                .query((rs, n) -> new AccountRow<>(rs.getObject("user_id", UUID.class),
                        Money.of(rs.getBigDecimal("balance")), 0, 0))
                .list();
        return accounts(rows, size, row -> row.balance().amount(), (row, holder) -> new WalletAccount(holder,
                row.balance()));
    }

    /** One user's wallet and its transactions; empty: no such user. */
    public Optional<AdminWalletHistory> walletAccount(UUID userId, String cursor, Integer limit) {
        Objects.requireNonNull(userId, "userId");
        return holder(userId).map(holder -> new AdminWalletHistory(
                new WalletAccount(holder, walletBalance(admin, userId)), walletPage(admin, userId, cursor, limit)));
    }

    // ------------------------------------------------------------------------------------------------------ reads

    /** The rows of one account page: balance and, for points, the net rewards. */
    private record AccountRow<B>(UUID userId, B balance, int bookingRewards, int referralRewards) {
    }

    private interface Combine<B, A> {
        A apply(AccountRow<B> row, Holder holder);
    }

    private <B, A> Page<A> accounts(List<AccountRow<B>> rows, int size, Function<AccountRow<B>, BigDecimal> balance,
                                    Combine<B, A> combine) {
        boolean more = rows.size() > size;
        List<AccountRow<B>> page = more ? rows.subList(0, size) : rows;
        Map<UUID, Holder> named = holders.find(page.stream().map(AccountRow::userId).toList());
        List<A> items = page.stream().map(row -> combine.apply(row, Optional.ofNullable(named.get(row.userId()))
                .orElseThrow(() -> new IllegalStateException("Loyalty account without a Customer: " + row.userId()))))
                .toList();
        String next = more ? LedgerCursor.encode(new LedgerCursor.Position(balance.apply(page.getLast()),
                page.getLast().userId())) : null;
        return new Page<>(items, next);
    }

    private PointsAccount rewards(Holder holder, int balance) {
        return admin.sql("""
                        SELECT COALESCE(sum(t.amount) FILTER (WHERE t.booking_id IS NOT NULL), 0) AS booking_rewards,
                               COALESCE(sum(t.amount) FILTER (WHERE t.referral_id IS NOT NULL), 0) AS referral_rewards
                          FROM loyalty.points_transaction t
                         WHERE t.points_account_id = :user""")
                .param("user", holder.userId())
                .query((rs, n) -> new PointsAccount(holder, balance, rs.getInt("booking_rewards"),
                        rs.getInt("referral_rewards")))
                .single();
    }

    /** {@code null}: no search, every account; otherwise the matching Customers, possibly none. */
    private UUID[] matching(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        Set<UUID> found = holders.search(q);
        return found.toArray(UUID[]::new);
    }

    private Optional<Holder> holder(UUID userId) {
        return Optional.ofNullable(holders.find(List.of(userId)).get(userId));
    }

    private static int pointsBalance(JdbcClient jdbc, UUID userId) {
        return jdbc.sql("SELECT balance FROM loyalty.points_account WHERE user_id = :user").param("user", userId)
                .query(Integer.class).single();
    }

    private static Money walletBalance(JdbcClient jdbc, UUID userId) {
        return Money.of(jdbc.sql("SELECT balance FROM loyalty.wallet_account WHERE user_id = :user")
                .param("user", userId).query(BigDecimal.class).single());
    }

    private static Page<PointsTransaction> pointsPage(JdbcClient jdbc, UUID userId, String cursor, Integer limit) {
        return page(jdbc, POINTS_PAGE, userId, cursor, limit, LoyaltyLedgers::pointsTransaction,
                PointsTransaction::transactionId);
    }

    private static Page<WalletTransaction> walletPage(JdbcClient jdbc, UUID userId, String cursor, Integer limit) {
        return page(jdbc, WALLET_PAGE, userId, cursor, limit, LoyaltyLedgers::walletTransaction,
                WalletTransaction::transactionId);
    }

    private static <T> Page<T> page(JdbcClient jdbc, String sql, UUID userId, String cursor, Integer limit,
                                    RowMapper<T> mapper, Function<T, UUID> id) {
        UUID after = LedgerCursor.transaction(cursor);
        int size = LedgerCursor.size(limit);
        List<T> rows = jdbc.sql(sql).param("user", userId).param("after", after).param("limit", size + 1)
                .query(mapper).list();
        boolean more = rows.size() > size;
        List<T> page = more ? rows.subList(0, size) : rows;
        return new Page<>(List.copyOf(page), more ? LedgerCursor.encode(id.apply(page.getLast())) : null);
    }

    static PointsTransaction pointsTransaction(ResultSet rs, int row) throws SQLException {
        return new PointsTransaction(rs.getObject("id", UUID.class), rs.getObject("points_account_id", UUID.class),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                PointsTransactionCause.valueOf(rs.getString("cause")), rs.getInt("requested_amount"),
                rs.getInt("amount"), rs.getInt("unrecovered_reversal_amount"), rs.getInt("balance_after"),
                rs.getObject("booking_id", UUID.class), rs.getObject("referral_id", UUID.class),
                rs.getObject("admin_id", UUID.class), rs.getString("reason"));
    }

    private static WalletTransaction walletTransaction(ResultSet rs, int row) throws SQLException {
        return new WalletTransaction(rs.getObject("id", UUID.class),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                WalletTransactionCause.valueOf(rs.getString("cause")), Money.of(rs.getBigDecimal("amount")),
                Money.of(rs.getBigDecimal("balance_after")), rs.getObject("booking_id", UUID.class));
    }

    /** A points-list cursor carries a whole number; anything else is not one of ours. */
    private static int wholePoints(BigDecimal balance) {
        try {
            return balance.intValueExact();
        } catch (ArithmeticException notWhole) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "cursor");
        }
    }
}
