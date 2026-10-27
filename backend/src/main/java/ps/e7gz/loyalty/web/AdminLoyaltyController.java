package ps.e7gz.loyalty.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.PointsTransactionCause;
import ps.e7gz.common.codes.ReferralInvalidationKind;
import ps.e7gz.common.codes.ReferralStatus;
import ps.e7gz.common.codes.WalletTransactionCause;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.money.Money;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.MoneyView;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.PageView;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.loyalty.api.AccountHolders;
import ps.e7gz.loyalty.internal.LoyaltyLedgers;
import ps.e7gz.loyalty.internal.PointsAdjustments;
import ps.e7gz.loyalty.internal.Referrals;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.ResultAggregate;
import ps.e7gz.messaging.api.StoredResponse;

/**
 * Platform Admin Wallet, Points and Referrals modules and the points configuration (arch 09 §5, FR-ADM-09, FR-PTS-09,
 * FR-PTS-11, arch 01 §14 A8): the Sprint 4 contract (E7-65). Wallet and points implemented by E7-66, referrals by E7-67;
 * settings (E7-68) are stubs until their task. The wallet is read-only here too: there is no admin wallet
 * adjustment (FR-PTS-11, PCR-004). Writes need a reason, are audited (FR-AUD-06), notify the customer (FR-NTF-11) and
 * require the Idempotency-Key (PO decision D6, 17 Oct 2026). Lists are cursor-paged (arch 09 §1.2); {@code q} matches
 * part of the full name, username or email, or the exact phone, trimmed and case-insensitive. A user's history sits
 * under {@code /admin/users/{userId}}.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "admin-loyalty")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminLoyaltyController {

    /** {@code technical.idempotency_record.endpoint} of the points adjustment (arch 08 section 6). */
    static final String ADJUST_ENDPOINT = "POST /api/v1/admin/points/adjust";

    /** {@code technical.idempotency_record.endpoint} of the referral invalidation. */
    static final String INVALIDATE_ENDPOINT = "POST /api/v1/admin/referrals/{referralId}/invalidate";

    private static final StoredResponse<AdminPointsTransaction> ADJUSTED = StoredResponse.of(
            AdminPointsTransaction.class, 200,
            (AdminPointsTransaction posted) -> new ResultAggregate("POINTS_TRANSACTION", posted.transactionId()));

    private static final StoredResponse<AdminReferralInvalidation> INVALIDATED = StoredResponse.of(
            AdminReferralInvalidation.class, 200,
            (AdminReferralInvalidation done) -> new ResultAggregate("REFERRAL", done.referral().referralId()));

    private final LoyaltyLedgers ledgers;
    private final PointsAdjustments adjustments;
    private final Referrals referrals;
    private final Idempotency idempotency;
    private final ResponseMetaFactory meta;

    AdminLoyaltyController(LoyaltyLedgers ledgers, PointsAdjustments adjustments, Referrals referrals,
                           Idempotency idempotency, ResponseMetaFactory meta) {
        this.ledgers = ledgers;
        this.adjustments = adjustments;
        this.referrals = referrals;
        this.idempotency = idempotency;
        this.meta = meta;
    }

    @GetMapping("/wallet")
    @Operation(operationId = "adminListWallets", summary = "Wallets: user, phone and balance (FR-ADM-09)",
            description = "Never venue gaming revenue (FR-ADM-09). Highest balance first. Implemented by E7-66.")
    public DataResponse<PageView<AdminWalletAccount>> wallets(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        LoyaltyLedgers.Page<LoyaltyLedgers.WalletAccount> page = ledgers.walletAccounts(q, cursor, limit);
        return meta.data(new PageView<>(page.items().stream().map(AdminWalletAccount::of).toList(),
                page.nextCursor()));
    }

    @GetMapping("/users/{userId}/wallet")
    @Operation(operationId = "adminGetUserWallet", summary = "One user's wallet and its transactions (FR-ADM-09)",
            description = "Newest first. Implemented by E7-66.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    public DataResponse<AdminWalletHistory> userWallet(
            @PathVariable UUID userId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        LoyaltyLedgers.AdminWalletHistory history = ledgers.walletAccount(userId, cursor, limit)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        return meta.data(new AdminWalletHistory(AdminWalletAccount.of(history.account()),
                history.page().items().stream().map(AdminWalletTransaction::of).toList(),
                history.page().nextCursor()));
    }

    @GetMapping("/points")
    @Operation(operationId = "adminListPoints",
            summary = "Points: user, balance, booking rewards and referral rewards (FR-ADM-09)",
            description = "Highest balance first. Implemented by E7-66.")
    public DataResponse<PageView<AdminPointsAccount>> points(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        LoyaltyLedgers.Page<LoyaltyLedgers.PointsAccount> page = ledgers.pointsAccounts(q, cursor, limit);
        return meta.data(new PageView<>(page.items().stream().map(AdminPointsAccount::of).toList(),
                page.nextCursor()));
    }

    @GetMapping("/users/{userId}/points")
    @Operation(operationId = "adminGetUserPoints", summary = "One user's points and their ledger (FR-ADM-09)",
            description = "Newest first, with requested, applied and unrecovered amounts (FR-PTS-04). Implemented by "
                    + "E7-66.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    public DataResponse<AdminPointsHistory> userPoints(
            @PathVariable UUID userId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        LoyaltyLedgers.AdminPointsHistory history = ledgers.pointsAccount(userId, cursor, limit)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        return meta.data(new AdminPointsHistory(AdminPointsAccount.of(history.account()),
                history.page().items().stream().map(AdminPointsTransaction::of).toList(),
                history.page().nextCursor()));
    }

    @PostMapping("/points/adjust")
    @Operation(operationId = "adminAdjustPoints", summary = "Add or deduct points (FR-PTS-11, FR-TRS-27)",
            description = "amount is non-zero (0 is VALIDATION_FAILED on amount). A deduction applies "
                    + "MIN(balance, requested); the shortfall is recorded as unrecoveredAmount and is never a debt "
                    + "(FR-PTS-03, FR-PTS-04). An anonymised account is ACCOUNT_DELETED. Implemented by E7-66.")
    @ApiError(status = 403, codes = ApiErrorCode.ACCOUNT_DELETED)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminPointsTransaction> adjustPoints(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @Valid @RequestBody AdminPointsAdjustRequest request,
            @AuthenticationPrincipal AuthenticatedSubject admin) {
        return meta.data(adjustments.adjust(admin.id(), request.userId(), request.amount(), request.reason(),
                idempotencyKey, idempotency.request(admin, ADJUST_ENDPOINT, idempotencyKey, Map.of(), request),
                ADJUSTED, AdminPointsTransaction::of).body());
    }

    @GetMapping("/referrals")
    @Operation(operationId = "adminListReferrals",
            summary = "Referrals: referrer, code, referred user, sign-up, reward applied, status (FR-ADM-09)",
            description = "status filters exactly; q matches the referrer or the referred user. Newest sign-up first. "
                    + "Implemented by E7-67.")
    public DataResponse<PageView<AdminReferral>> referrals(
            @RequestParam(required = false) ReferralStatus status,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        LoyaltyLedgers.Page<Referrals.AdminReferral> page = referrals.list(status, q, cursor, limit);
        return meta.data(new PageView<>(page.items().stream().map(AdminReferral::of).toList(), page.nextCursor()));
    }

    @PostMapping("/referrals/{referralId}/invalidate")
    @Operation(operationId = "adminInvalidateReferral", summary = "Invalidate a referral: INVALID (FR-PTS-09)",
            description = "A REWARDED referral also gets a compensating transaction for the exact historical reward, "
                    + "under the zero floor (FR-PTS-09); a PENDING one is never rewarded. Recorded as ADMIN_FRAUD. An "
                    + "INVALID referral is ADMIN_ACTION_NOT_ALLOWED. Implemented by E7-67.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminReferralInvalidation> invalidateReferral(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID referralId,
            @Valid @RequestBody AdminReferralInvalidateRequest request,
            @AuthenticationPrincipal AuthenticatedSubject admin) {
        return meta.data(referrals.invalidate(admin.id(), referralId, request.reason(),
                idempotency.request(admin, INVALIDATE_ENDPOINT, idempotencyKey, Map.of("referralId", referralId),
                        request),
                INVALIDATED, AdminReferralInvalidation::of).body());
    }

    @GetMapping("/points/settings")
    @Operation(operationId = "adminGetPointsSettings",
            summary = "Completed-booking points, referral points and the wallet-refund switch (A8)",
            description = "Each value with the instant it took effect. Implemented by E7-68.")
    public DataResponse<AdminPointsSettings> pointsSettings() {
        throw NotImplementedYet.stub();
    }

    @PutMapping("/points/settings")
    @Operation(operationId = "adminChangePointsSettings", summary = "Change points values or the wallet-refund switch",
            description = "PO decision D3 (17 Oct 2026). Omitted fields keep their value; a value equal to the current "
                    + "one changes nothing. Every change takes effect now and only for later events: history keeps the "
                    + "value it was granted with (FR-ADM-11, FR-PTS-01). Points are whole numbers from 0; none given "
                    + "is VALIDATION_FAILED. Through loyalty.configure. Implemented by E7-68.")
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminPointsSettings> changePointsSettings(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @Valid @RequestBody AdminPointsSettingsChangeRequest request) {
        throw NotImplementedYet.stub();
    }

    @Schema(name = "AdminWalletAccount")
    public record AdminWalletAccount(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID userId,
            @NotNull @Schema(example = "Omar Hassan") String fullName,
            @NotNull @Schema(example = "omar_h") String username,
            @NotNull @Schema(example = "+201001234567") String phone,
            @NotNull MoneyView balance) {

        /** An anonymised account has no phone any more (FR-ACC-10): it is shown empty, like its name. */
        static AdminWalletAccount of(LoyaltyLedgers.WalletAccount account) {
            AccountHolders.Holder holder = account.holder();
            return new AdminWalletAccount(holder.userId(), holder.fullName(), holder.username(),
                    holder.phone() == null ? "" : holder.phone(), MoneyView.of(account.balance()));
        }
    }

    @Schema(name = "AdminWalletHistory")
    public record AdminWalletHistory(
            @NotNull AdminWalletAccount account,
            @NotNull List<AdminWalletTransaction> items,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "eyJvZmZzZXQiOjIwfQ")
            String nextCursor) {
    }

    @Schema(name = "AdminWalletTransaction")
    public record AdminWalletTransaction(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID transactionId,
            @NotNull @Schema(example = "2026-10-20T19:00:00Z") Instant occurredAt,
            @NotNull WalletTransactionCause cause,
            @NotNull @Schema(description = "How much moved, never negative: BOOKING_FEE_REFUND adds it, "
                    + "BOOKING_FEE_PAYMENT spends it on a later booking's fee (PCR-009).") MoneyView amount,
            @NotNull MoneyView balanceAfter,
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e70") UUID bookingId) {

        static AdminWalletTransaction of(LoyaltyLedgers.WalletTransaction row) {
            return new AdminWalletTransaction(row.transactionId(), row.occurredAt(), row.cause(),
                    MoneyView.of(Money.of(row.amount().amount().abs())), MoneyView.of(row.balanceAfter()),
                    row.bookingId());
        }
    }

    @Schema(name = "AdminPointsAccount")
    public record AdminPointsAccount(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID userId,
            @NotNull @Schema(example = "Omar Hassan") String fullName,
            @NotNull @Schema(example = "omar_h") String username,
            @NotNull @Schema(example = "1200") Integer balance,
            @NotNull @Schema(description = "Net points from completed bookings.", example = "200")
            Integer bookingRewards,
            @NotNull @Schema(description = "Net points from referrals.", example = "1000") Integer referralRewards) {

        static AdminPointsAccount of(LoyaltyLedgers.PointsAccount account) {
            return new AdminPointsAccount(account.holder().userId(), account.holder().fullName(),
                    account.holder().username(), account.balance(), account.bookingRewards(),
                    account.referralRewards());
        }
    }

    @Schema(name = "AdminPointsHistory")
    public record AdminPointsHistory(
            @NotNull AdminPointsAccount account,
            @NotNull List<AdminPointsTransaction> items,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "eyJvZmZzZXQiOjIwfQ")
            String nextCursor) {
    }

    /** A points ledger row as the admin sees it (FR-PTS-04, FR-PTS-09). */
    @Schema(name = "AdminPointsTransaction")
    public record AdminPointsTransaction(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID transactionId,
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e70") UUID userId,
            @NotNull @Schema(example = "2026-10-20T22:05:00Z") Instant occurredAt,
            @NotNull PointsTransactionCause cause,
            @NotNull @Schema(example = "-1000") Integer requestedAmount,
            @NotNull @Schema(description = "Applied amount.", example = "-300") Integer amount,
            @NotNull @Schema(description = "UNRECOVERED_REVERSAL_AMOUNT: never a debt.", example = "700")
            Integer unrecoveredAmount,
            @NotNull @Schema(example = "0") Integer balanceAfter,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID bookingId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID referralId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID adminId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    example = "Duplicate reward for one booking.") String reason) {

        static AdminPointsTransaction of(LoyaltyLedgers.PointsTransaction row) {
            return new AdminPointsTransaction(row.transactionId(), row.userId(), row.occurredAt(), row.cause(),
                    row.requestedAmount(), row.amount(), row.unrecoveredAmount(), row.balanceAfter(), row.bookingId(),
                    row.referralId(), row.adminId(), row.reason());
        }
    }

    @Schema(name = "AdminPointsAdjustRequest")
    public record AdminPointsAdjustRequest(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID userId,
            @NotNull @Schema(description = "Non-zero: positive adds, negative deducts.", example = "-200")
            Integer amount,
            @NotBlank @Size(min = 1, max = 500) @Schema(example = "Duplicate reward for one booking.") String reason) {
    }

    @Schema(name = "AdminReferral")
    public record AdminReferral(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID referralId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "Null when the code matched no one (INVALID_CODE).") UUID referrerUserId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "sara_k")
            String referrerUsername,
            @NotNull @Schema(example = "K7M3QX9P") String code,
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e70") UUID referredUserId,
            @NotNull @Schema(example = "omar_h") String referredUsername,
            @NotNull @Schema(example = "2026-10-18T10:00:00Z") Instant signedUpAt,
            @NotNull ReferralStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "1000")
            Integer rewardPointsApplied,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-10-18T10:03:00Z")
            Instant rewardedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Instant invalidatedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            ReferralInvalidationKind invalidationKind) {

        static AdminReferral of(Referrals.AdminReferral r) {
            return new AdminReferral(r.referralId(), r.referrerUserId(), r.referrerUsername(), r.code(),
                    r.referredUserId(), r.referredUsername(), r.signedUpAt(), r.status(), r.rewardPointsApplied(),
                    r.rewardedAt(), r.invalidatedAt(), r.invalidationKind());
        }
    }

    @Schema(name = "AdminReferralInvalidateRequest")
    public record AdminReferralInvalidateRequest(
            @NotBlank @Size(min = 1, max = 500) @Schema(example = "Same person on two phones.") String reason) {
    }

    /** The referral after invalidation, and the compensating transaction when it had been rewarded (FR-PTS-09). */
    @Schema(name = "AdminReferralInvalidation")
    public record AdminReferralInvalidation(
            @NotNull AdminReferral referral,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) AdminPointsTransaction reversal) {

        static AdminReferralInvalidation of(Referrals.Invalidation done) {
            return new AdminReferralInvalidation(AdminReferral.of(done.referral()),
                    done.reversal() == null ? null : AdminPointsTransaction.of(done.reversal()));
        }
    }

    @Schema(name = "AdminPointsSettings")
    public record AdminPointsSettings(
            @NotNull @Schema(description = "Points for a completed booking with qualifying attendance (200 at launch).")
            AdminConfiguredInteger attendedPoints,
            @NotNull @Schema(description = "Points for a referral sign-up (1,000 at launch).")
            AdminConfiguredInteger referralPoints,
            @NotNull @Schema(description = "Whether booking-fee refunds credit the wallet (false at launch).")
            AdminConfiguredBoolean walletRefundsEnabled) {
    }

    @Schema(name = "AdminConfiguredInteger")
    public record AdminConfiguredInteger(
            @NotNull @Schema(example = "200") Integer value,
            @NotNull @Schema(description = "When this value took effect.", example = "2026-10-01T00:00:00Z")
            Instant effectiveFrom) {
    }

    @Schema(name = "AdminConfiguredBoolean")
    public record AdminConfiguredBoolean(
            @NotNull @Schema(example = "false") Boolean value,
            @NotNull @Schema(description = "When this value took effect.", example = "2026-10-01T00:00:00Z")
            Instant effectiveFrom) {
    }

    @Schema(name = "AdminPointsSettingsChangeRequest")
    public record AdminPointsSettingsChangeRequest(
            @Min(0) @Schema(nullable = true, example = "250") Integer attendedPoints,
            @Min(0) @Schema(nullable = true, example = "1000") Integer referralPoints,
            @Schema(nullable = true, example = "false") Boolean walletRefundsEnabled,
            @NotBlank @Size(min = 1, max = 500) @Schema(example = "Autumn promotion.") String reason) {
    }
}
