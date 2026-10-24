package ps.e7gz.loyalty.internal;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.loyalty.api.AccountHolders;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.StoredResponse;

/**
 * The Platform Admin's points adjustment (FR-PTS-11: a launch action, unlike a wallet adjustment; FR-TRS-27, FR-AUD-06;
 * Sprint 4 contract, E7-65). One {@code CommandRunner} transaction on the admin connection, wrapped by
 * {@link Idempotency} (ADR-015, PO decision D6): {@code loyalty.adjust_points} writes the ledger row, the balance, the
 * audit (with the admin and the reason) and the {@code LOYALTY_POINTS_LEDGER_COMMITTED} event that notifies the
 * customer (FR-NTF-11), and the Idempotency-Key record commits with them.
 *
 * <p>The producer applies the deduction rule itself - {@code MIN(balance, requested)}, the shortfall recorded as
 * UNRECOVERED_REVERSAL_AMOUNT and never a debt (FR-PTS-03, FR-PTS-04) - so this code does not re-check it. The
 * Idempotency-Key is also the producer's request id: its operation key is {@code ADMIN:<admin>:<key>}.
 */
@Component
public class PointsAdjustments {

    static final String BEGIN = "SELECT loyalty.begin_admin_command(ARRAY[CAST(:user AS uuid)], :admin, :correlation)";
    static final String ADJUST = "SELECT loyalty.adjust_points(:user, :amount, :request, :reason)";

    private final CommandRunner runner;
    private final Idempotency idempotency;
    private final AccountHolders holders;

    PointsAdjustments(CommandRunner runner, Idempotency idempotency, AccountHolders holders) {
        this.runner = runner;
        this.idempotency = idempotency;
        this.holders = holders;
    }

    /**
     * Adds ({@code amount > 0}) or deducts ({@code amount < 0}) points once per Idempotency-Key. Zero is
     * {@code VALIDATION_FAILED} on {@code amount}; a user that is not a Customer is {@code NOT_FOUND}, both before any
     * idempotency record is written; an anonymised account is {@code ACCOUNT_DELETED} (FR-ACC-10).
     */
    public <R> IdempotentResult<R> adjust(UUID adminId, UUID userId, int amount, String reason, UUID idempotencyKey,
                                          IdempotentRequest key, StoredResponse<R> response,
                                          Function<LoyaltyLedgers.PointsTransaction, R> view) {
        Objects.requireNonNull(view, "view");
        Command<LoyaltyLedgers.PointsTransaction> command = command(adminId, userId, amount, reason, idempotencyKey);
        return runner.run(idempotency.wrap(Objects.requireNonNull(key, "key"), response, new Mapped<>(command, view)));
    }

    /** The same command without an Idempotency-Key record: package-private, for this package's tests. */
    LoyaltyLedgers.PointsTransaction adjust(UUID adminId, UUID userId, int amount, String reason, UUID requestId) {
        return runner.run(command(adminId, userId, amount, reason, requestId));
    }

    private Command<LoyaltyLedgers.PointsTransaction> command(UUID adminId, UUID userId, int amount, String reason,
                                                              UUID requestId) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(requestId, "requestId");
        if (amount == 0) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "amount");
        }
        // begin_command locks the User, so the User must exist before the command opens
        if (holders.find(List.of(userId)).isEmpty()) {
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        }
        return new Adjust(adminId, CorrelationId.require(), userId, amount, reason.strip(), requestId, holders);
    }

    /** No Venue in scope: category A, as every admin command that names none (ADR-022). Locks the Customer. */
    private record Adjust(UUID adminId, UUID correlationId, UUID userId, int amount, String reason, UUID requestId,
                          AccountHolders holders) implements Command<LoyaltyLedgers.PointsTransaction> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.admin(adminId, correlationId, LockSet.builder().users(userId).build());
        }

        @Override
        public LoyaltyLedgers.PointsTransaction execute(CommandContext context) {
            // read under the User's lock, so an anonymisation cannot slip in before the commit
            AccountHolders.Holder holder = holders.find(context, userId)
                    .orElseThrow(() -> new IllegalStateException("Customer " + userId + " vanished under its lock"));
            if (holder.deleted()) {
                throw new ApiException(ApiErrorCode.ACCOUNT_DELETED);
            }
            context.jdbc().sql(BEGIN).param("user", userId).param("admin", adminId)
                    .param("correlation", correlationId).query().singleRow();
            UUID transaction = context.jdbc().sql(ADJUST).param("user", userId).param("amount", amount)
                    .param("request", requestId).param("reason", reason).query(UUID.class).single();
            return context.jdbc().sql("""
                            SELECT t.id, t.points_account_id, t.occurred_at, t.cause, t.requested_amount, t.amount,
                                   t.unrecovered_reversal_amount, t.balance_after, t.booking_id, t.referral_id,
                                   t.admin_id, t.reason
                              FROM loyalty.points_transaction t
                             WHERE t.id = :id""")
                    .param("id", transaction).query(LoyaltyLedgers::pointsTransaction).single();
        }
    }

    /** {@code business}, its result mapped to the response: the same category and lock set. */
    private record Mapped<T, R>(Command<T> business, Function<T, R> view) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return business.eligibilityCategory();
        }

        @Override
        public CommandScope scope() {
            return business.scope();
        }

        @Override
        public R execute(CommandContext context) {
            return view.apply(business.execute(context));
        }
    }
}
