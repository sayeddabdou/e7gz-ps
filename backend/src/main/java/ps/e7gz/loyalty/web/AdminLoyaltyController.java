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
import java.util.UUID;
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
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.MoneyView;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.PageView;

/**
 * Platform Admin Wallet, Points and Referrals modules and the points configuration (arch 09 §5, FR-ADM-09, FR-PTS-09,
 * FR-PTS-11, arch 01 §14 A8). Stubs of the Sprint 4 contract (E7-65) until E7-66 (wallet, points), E7-67 (referrals)
 * and E7-68 (settings). The wallet is read-only here too: there is no admin wallet adjustment (FR-PTS-11, PCR-004).
 * Writes need a reason, are audited (FR-AUD-06), notify the customer (FR-NTF-11) and require the Idempotency-Key (PO
 * decision D6, 17 Oct 2026). Lists are cursor-paged (arch 09 §1.2); {@code q} matches part of the full name, username
 * or email, or the exact phone, trimmed and case-insensitive. A user's history sits under {@code /admin/users/{userId}}.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "admin-loyalty")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminLoyaltyController {

    @GetMapping("/wallet")
    @Operation(operationId = "adminListWallets", summary = "Wallets: user, phone and balance (FR-ADM-09)",
            description = "Never venue gaming revenue (FR-ADM-09). Highest balance first. Implemented by E7-66.")
    public DataResponse<PageView<AdminWalletAccount>> wallets(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        throw NotImplementedYet.stub();
    }

    @GetMapping("/users/{userId}/wallet")
    @Operation(operationId = "adminGetUserWallet", summary = "One user's wallet and its transactions (FR-ADM-09)",
            description = "Newest first. Implemented by E7-66.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    public DataResponse<AdminWalletHistory> userWallet(
            @PathVariable UUID userId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        throw NotImplementedYet.stub();
    }

    @GetMapping("/points")
    @Operation(operationId = "adminListPoints",
            summary = "Points: user, balance, booking rewards and referral rewards (FR-ADM-09)",
            description = "Highest balance first. Implemented by E7-66.")
    public DataResponse<PageView<AdminPointsAccount>> points(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        throw NotImplementedYet.stub();
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
        throw NotImplementedYet.stub();
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
            @Valid @RequestBody AdminPointsAdjustRequest request) {
        throw NotImplementedYet.stub();
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
        throw NotImplementedYet.stub();
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
            @Valid @RequestBody AdminReferralInvalidateRequest request) {
        throw NotImplementedYet.stub();
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
            @NotNull MoneyView amount,
            @NotNull MoneyView balanceAfter,
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e70") UUID bookingId) {
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
