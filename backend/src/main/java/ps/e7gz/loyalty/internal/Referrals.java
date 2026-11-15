package ps.e7gz.loyalty.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.ReferralInvalidationKind;
import ps.e7gz.common.codes.ReferralStatus;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.persistence.DataSourceConfig;
import ps.e7gz.loyalty.api.AccountHolders;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.StoredResponse;

/**
 * Referrals (Product v5.0 §46.4-§46.5, FR-PTS-06 to FR-PTS-09, FR-ADM-09; E7-67): the customer's own code and the
 * sign-ups that used it, the Platform Admin's list, and the admin's fraud invalidation. The referral is recorded by the
 * database at registration ({@code loyalty.initialize_user}) and settled at the friend's OTP verification
 * ({@link JdbcReferralSettlement}); these are its reads and its one admin write.
 *
 * <p>The invalidation is one {@code CommandRunner} transaction on the admin connection, wrapped by {@link Idempotency}
 * (PO decision D6): {@code loyalty.invalidate_referral} makes it INVALID as ADMIN_FRAUD with the admin and the reason,
 * and for a REWARDED referral posts the compensating transaction of the exact historical reward under the zero floor -
 * the shortfall recorded as UNRECOVERED_REVERSAL_AMOUNT, never a debt (FR-PTS-03, FR-PTS-04, FR-PTS-09). Its
 * {@code LOYALTY_POINTS_LEDGER_COMMITTED} event notifies the referrer when points were taken back (FR-NTF-11; PO
 * decision 30 Oct 2026). An INVALID referral is {@code ADMIN_ACTION_NOT_ALLOWED} (D5).
 *
 * <p>Lists are newest sign-up first with keyset cursors (arch 09 section 1.2). The friend's identity is never in the
 * customer's view.
 */
@Component
public class Referrals {

    /** A sign-up that used my code; the friend is not named. */
    public record CustomerReferral(UUID referralId, ReferralStatus status, Instant signedUpAt, Instant rewardedAt,
                                   Integer rewardPoints) {
    }

    /** My code to share and my referrals. */
    public record MyReferrals(String referralCode, LoyaltyLedgers.Page<CustomerReferral> page) {
    }

    /** A referral as the Platform Admin sees it (FR-ADM-09); the referrer is empty for an unknown code. */
    public record AdminReferral(UUID referralId, UUID referrerUserId, String referrerUsername, String code,
                                UUID referredUserId, String referredUsername, Instant signedUpAt, ReferralStatus status,
                                Integer rewardPointsApplied, Instant rewardedAt, Instant invalidatedAt,
                                ReferralInvalidationKind invalidationKind) {
    }

    /** The invalidated referral and, when it had been rewarded, its compensating transaction (FR-PTS-09). */
    public record Invalidation(AdminReferral referral, LoyaltyLedgers.PointsTransaction reversal) {
    }

    static final String COLUMNS = """
            r.id, r.referrer_user_id, r.referred_user_id, r.code, r.status, r.reward_value_applied, r.created_at,
            r.rewarded_at, r.invalidated_at, r.invalidation_kind""";

    static final String MINE = "SELECT " + COLUMNS + """
              FROM loyalty.referral r
             WHERE r.referrer_user_id = :user
               AND (CAST(:after AS uuid) IS NULL
                    OR (r.created_at, r.id) < (SELECT c.created_at, c.id FROM loyalty.referral c
                                                WHERE c.id = :after AND c.referrer_user_id = :user))
             ORDER BY r.created_at DESC, r.id DESC
             LIMIT :limit""";

    static final String ALL = "SELECT " + COLUMNS + """
              FROM loyalty.referral r
             WHERE (CAST(:status AS text) IS NULL OR r.status = CAST(:status AS text))
               AND (CAST(:ids AS uuid[]) IS NULL OR r.referrer_user_id = ANY(CAST(:ids AS uuid[]))
                    OR r.referred_user_id = ANY(CAST(:ids AS uuid[])))
               AND (CAST(:after AS uuid) IS NULL
                    OR (r.created_at, r.id) < (SELECT c.created_at, c.id FROM loyalty.referral c WHERE c.id = :after))
             ORDER BY r.created_at DESC, r.id DESC
             LIMIT :limit""";

    static final String ONE = "SELECT " + COLUMNS + " FROM loyalty.referral r WHERE r.id = :id";

    static final String BEGIN = "SELECT loyalty.begin_admin_command(CAST(:users AS uuid[]), :admin, :correlation)";
    static final String INVALIDATE = "SELECT loyalty.invalidate_referral(:id, :reason)";
    static final String REVERSAL = """
            SELECT t.id, t.points_account_id, t.occurred_at, t.cause, t.requested_amount, t.amount,
                   t.unrecovered_reversal_amount, t.balance_after, t.booking_id, t.referral_id, t.admin_id, t.reason
              FROM loyalty.points_transaction t
             WHERE t.referral_id = :id AND t.cause = 'COMPENSATION'""";

    private final CommandRunner runner;
    private final Idempotency idempotency;
    private final AccountHolders holders;
    private final JdbcClient app;
    private final JdbcClient admin;

    Referrals(CommandRunner runner, Idempotency idempotency, AccountHolders holders, DataSource appDataSource,
              @Qualifier(DataSourceConfig.ADMIN_DATA_SOURCE) DataSource adminDataSource) {
        this.runner = runner;
        this.idempotency = idempotency;
        this.holders = holders;
        this.app = JdbcClient.create(appDataSource);
        this.admin = JdbcClient.create(adminDataSource);
    }

    /** The customer's own code and the sign-ups that used it (FR-PTS-06, FR-PTS-07). */
    public MyReferrals mine(UUID userId, String cursor, Integer limit) {
        Objects.requireNonNull(userId, "userId");
        UUID after = LedgerCursor.transaction(cursor);
        int size = LedgerCursor.size(limit);
        List<Row> rows = app.sql(MINE).param("user", userId).param("after", after).param("limit", size + 1)
                .query((rs, n) -> row(rs)).list();
        String code = holders.referralCode(userId)
                .orElseThrow(() -> new IllegalStateException("Customer " + userId + " has no account"));
        List<CustomerReferral> page = rows.stream().limit(size)
                .map(r -> new CustomerReferral(r.id(), r.status(), r.signedUpAt(), r.rewardedAt(), r.rewardPoints()))
                .toList();
        return new MyReferrals(code, new LoyaltyLedgers.Page<>(page,
                rows.size() > size ? LedgerCursor.encode(page.getLast().referralId()) : null));
    }

    /**
     * The Platform Admin's list: {@code status} filters exactly, {@code q} matches the referrer or the referred user
     * as {@link AccountHolders#search} does; newest sign-up first.
     */
    public LoyaltyLedgers.Page<AdminReferral> list(ReferralStatus status, String q, String cursor, Integer limit) {
        UUID after = LedgerCursor.transaction(cursor);
        int size = LedgerCursor.size(limit);
        UUID[] ids = null;
        if (q != null && !q.isBlank()) {
            Set<UUID> matching = holders.search(q);
            if (matching.isEmpty()) {
                return new LoyaltyLedgers.Page<>(List.of(), null);
            }
            ids = matching.toArray(UUID[]::new);
        }
        List<Row> rows = admin.sql(ALL).param("status", status == null ? null : status.name()).param("ids", ids)
                .param("after", after).param("limit", size + 1).query((rs, n) -> row(rs)).list();
        List<Row> shown = rows.stream().limit(size).toList();
        List<AdminReferral> page = views(shown);
        return new LoyaltyLedgers.Page<>(page, rows.size() > size ? LedgerCursor.encode(page.getLast().referralId())
                : null);
    }

    /**
     * Invalidates the referral once per Idempotency-Key. An unknown referral is {@code NOT_FOUND} before any
     * idempotency record; an INVALID one is {@code ADMIN_ACTION_NOT_ALLOWED}, decided under the lock.
     */
    public <R> IdempotentResult<R> invalidate(UUID adminId, UUID referralId, String reason, IdempotentRequest key,
                                              StoredResponse<R> response, Function<Invalidation, R> view) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(view, "view");
        Row found = one(admin, referralId).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        List<UUID> users = new ArrayList<>();
        if (found.referrerUserId() != null) {
            users.add(found.referrerUserId());
        }
        users.add(found.referredUserId());
        Command<R> command = new Invalidate<>(adminId, CorrelationId.require(), referralId, List.copyOf(users),
                reason.strip(), this, view);
        return runner.run(idempotency.wrap(Objects.requireNonNull(key, "key"), response, command));
    }

    private Invalidation invalidateLocked(CommandContext context, UUID adminId, UUID correlationId, UUID referralId,
                                          List<UUID> users, String reason) {
        Row row = one(context.jdbc(), referralId)
                .orElseThrow(() -> new IllegalStateException("Referral " + referralId + " vanished under its lock"));
        if (row.status() == ReferralStatus.INVALID) {
            throw new ApiException(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);                // D5
        }
        context.jdbc().sql(BEGIN).param("users", users.toArray(UUID[]::new)).param("admin", adminId)
                .param("correlation", correlationId).query().singleRow();
        context.jdbc().sql(INVALIDATE).param("id", referralId).param("reason", reason).query().singleRow();
        Row after = one(context.jdbc(), referralId).orElseThrow();
        LoyaltyLedgers.PointsTransaction reversal = context.jdbc().sql(REVERSAL).param("id", referralId)
                .query(LoyaltyLedgers::pointsTransaction).optional().orElse(null);
        return new Invalidation(views(List.of(after)).getFirst(), reversal);
    }

    private List<AdminReferral> views(List<Row> rows) {
        Set<UUID> ids = new LinkedHashSet<>();
        rows.forEach(r -> {
            if (r.referrerUserId() != null) {
                ids.add(r.referrerUserId());
            }
            ids.add(r.referredUserId());
        });
        Map<UUID, AccountHolders.Holder> named = holders.find(ids);
        return rows.stream().map(r -> new AdminReferral(r.id(), r.referrerUserId(),
                r.referrerUserId() == null || !named.containsKey(r.referrerUserId()) ? null
                        : named.get(r.referrerUserId()).username(),
                r.code(), r.referredUserId(),
                named.containsKey(r.referredUserId()) ? named.get(r.referredUserId()).username() : "",
                r.signedUpAt(), r.status(), r.rewardPoints(), r.rewardedAt(), r.invalidatedAt(), r.invalidationKind()))
                .toList();
    }

    private static Optional<Row> one(JdbcClient jdbc, UUID referralId) {
        return jdbc.sql(ONE).param("id", Objects.requireNonNull(referralId, "referralId")).query((rs, n) -> row(rs))
                .optional();
    }

    private record Row(UUID id, UUID referrerUserId, UUID referredUserId, String code, ReferralStatus status,
                       Integer rewardPoints, Instant signedUpAt, Instant rewardedAt, Instant invalidatedAt,
                       ReferralInvalidationKind invalidationKind) {
    }

    private static Row row(ResultSet rs) throws SQLException {
        String kind = rs.getString("invalidation_kind");
        return new Row(rs.getObject("id", UUID.class), rs.getObject("referrer_user_id", UUID.class),
                rs.getObject("referred_user_id", UUID.class), rs.getString("code"),
                ReferralStatus.valueOf(rs.getString("status")), (Integer) rs.getObject("reward_value_applied"),
                instant(rs, "created_at"), instant(rs, "rewarded_at"), instant(rs, "invalidated_at"),
                kind == null ? null : ReferralInvalidationKind.valueOf(kind));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
        return at == null ? null : at.toInstant();
    }

    /** No Venue in scope: category A, as every admin command that names none (ADR-022). Locks both Users. */
    private record Invalidate<R>(UUID adminId, UUID correlationId, UUID referralId, List<UUID> users, String reason,
                                 Referrals referrals, Function<Invalidation, R> view) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.admin(adminId, correlationId, LockSet.builder().users(users).build());
        }

        @Override
        public R execute(CommandContext context) {
            return view.apply(referrals.invalidateLocked(context, adminId, correlationId, referralId, users, reason));
        }
    }
}
