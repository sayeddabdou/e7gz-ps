package ps.e7gz.loyalty.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.PointsTransactionCause;
import ps.e7gz.common.codes.ReferralStatus;
import ps.e7gz.common.codes.WalletTransactionCause;
import ps.e7gz.common.money.Money;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.MoneyView;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.loyalty.internal.LoyaltyLedgers;
import ps.e7gz.loyalty.internal.Referrals;

/**
 * The customer's points, referrals and wallet (arch 09 §3.4, FR-PTS-01, FR-PTS-06, PCR-004): the Sprint 4 contract
 * (E7-65); points and wallet implemented by E7-66, referrals by E7-67. All three are reads, open to every customer
 * state. Each list is newest first with cursor paging (arch 09 §1.2); the balance comes with the first page and every
 * later one. The wallet is read-only: no top-up, no pay-with-wallet, no withdrawal (PCR-004).
 */
@RestController
@RequestMapping("/api/v1/customer")
@Tag(name = "customer-loyalty")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class CustomerLoyaltyController {

    private final LoyaltyLedgers ledgers;
    private final Referrals referrals;
    private final ResponseMetaFactory meta;

    CustomerLoyaltyController(LoyaltyLedgers ledgers, Referrals referrals, ResponseMetaFactory meta) {
        this.ledgers = ledgers;
        this.referrals = referrals;
        this.meta = meta;
    }

    @GetMapping("/points")
    @Operation(operationId = "customerGetPoints", summary = "My points balance and history (FR-PTS-01, FR-PTS-05)",
            description = "The balance is never negative (FR-PTS-03). amount is what was applied: a reversal of 1,000 "
                    + "at a balance of 300 shows -300. Implemented by E7-66.")
    public DataResponse<CustomerPoints> points(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            @AuthenticationPrincipal AuthenticatedSubject customer) {
        LoyaltyLedgers.PointsHistory points = ledgers.points(customer.id(), cursor, limit);
        return meta.data(new CustomerPoints(points.balance(), points.page().items().stream()
                .map(CustomerPointsTransaction::of).toList(), points.page().nextCursor()));
    }

    @GetMapping("/referrals")
    @Operation(operationId = "customerGetReferrals",
            summary = "My referral code and the sign-ups that used it (FR-PTS-06, FR-PTS-07)",
            description = "A referral is PENDING until the friend verifies their phone, then REWARDED with the value in "
                    + "force at that moment, or INVALID (FR-PTS-06). Newest sign-up first; the friend is not named. "
                    + "Implemented by E7-67.")
    public DataResponse<CustomerReferrals> referrals(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            @AuthenticationPrincipal AuthenticatedSubject customer) {
        Referrals.MyReferrals mine = referrals.mine(customer.id(), cursor, limit);
        return meta.data(new CustomerReferrals(mine.referralCode(), mine.page().items().stream()
                .map(CustomerReferral::of).toList(), mine.page().nextCursor()));
    }

    @GetMapping("/wallet")
    @Operation(operationId = "customerGetWallet",
            summary = "My wallet: balance and booking-fee refunds, read-only (PCR-004, FR-PTS-10)",
            description = "While the booking fee is EGP 0 the balance is normally 0.00 with no transactions (PCR-004). "
                    + "The wallet never pays the venue gaming charge (FR-PTS-10); it pays the booking fee of a later "
                    + "booking when it holds at least the whole fee (PCR-009). Implemented by E7-66.")
    public DataResponse<CustomerWallet> wallet(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            @AuthenticationPrincipal AuthenticatedSubject customer) {
        LoyaltyLedgers.WalletHistory wallet = ledgers.wallet(customer.id(), cursor, limit);
        return meta.data(new CustomerWallet(MoneyView.of(wallet.balance()), wallet.page().items().stream()
                .map(CustomerWalletTransaction::of).toList(), wallet.page().nextCursor()));
    }

    @Schema(name = "CustomerPoints")
    public record CustomerPoints(
            @NotNull @Schema(example = "1200") Integer balance,
            @NotNull List<CustomerPointsTransaction> items,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "eyJvZmZzZXQiOjIwfQ")
            String nextCursor) {
    }

    @Schema(name = "CustomerPointsTransaction")
    public record CustomerPointsTransaction(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID transactionId,
            @NotNull @Schema(example = "2026-10-20T22:05:00Z") Instant occurredAt,
            @NotNull PointsTransactionCause cause,
            @NotNull @Schema(description = "Applied amount: positive earned, negative reversed.", example = "200")
            Integer amount,
            @NotNull @Schema(example = "1200") Integer balanceAfter,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID bookingId) {

        /** The applied amount only: the requested and unrecovered amounts are the Platform Admin's view. */
        static CustomerPointsTransaction of(LoyaltyLedgers.PointsTransaction row) {
            return new CustomerPointsTransaction(row.transactionId(), row.occurredAt(), row.cause(), row.amount(),
                    row.balanceAfter(), row.bookingId());
        }
    }

    @Schema(name = "CustomerReferrals")
    public record CustomerReferrals(
            @NotNull @Schema(description = "My code to share; its format is identity's (E7-13).", example = "K7M3QX9P")
            String referralCode,
            @NotNull List<CustomerReferral> items,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "eyJvZmZzZXQiOjIwfQ")
            String nextCursor) {
    }

    /** A sign-up that used my code. The friend's identity is not shown. */
    @Schema(name = "CustomerReferral")
    public record CustomerReferral(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID referralId,
            @NotNull ReferralStatus status,
            @NotNull @Schema(example = "2026-10-18T10:00:00Z") Instant signedUpAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-10-18T10:03:00Z")
            Instant rewardedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "The points granted, stored at verification (FR-PTS-07).", example = "1000")
            Integer rewardPoints) {

        static CustomerReferral of(Referrals.CustomerReferral referral) {
            return new CustomerReferral(referral.referralId(), referral.status(), referral.signedUpAt(),
                    referral.rewardedAt(), referral.rewardPoints());
        }
    }

    @Schema(name = "CustomerWallet")
    public record CustomerWallet(
            @NotNull MoneyView balance,
            @NotNull List<CustomerWalletTransaction> items,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "eyJvZmZzZXQiOjIwfQ")
            String nextCursor) {
    }

    @Schema(name = "CustomerWalletTransaction")
    public record CustomerWalletTransaction(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID transactionId,
            @NotNull @Schema(example = "2026-10-20T19:00:00Z") Instant occurredAt,
            @NotNull WalletTransactionCause cause,
            @NotNull @Schema(description = "How much moved, never negative: BOOKING_FEE_REFUND adds it, "
                    + "BOOKING_FEE_PAYMENT spends it on a later booking's fee (PCR-009).") MoneyView amount,
            @NotNull MoneyView balanceAfter,
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e70") UUID bookingId) {

        static CustomerWalletTransaction of(LoyaltyLedgers.WalletTransaction row) {
            return new CustomerWalletTransaction(row.transactionId(), row.occurredAt(), row.cause(),
                    MoneyView.of(Money.of(row.amount().amount().abs())), MoneyView.of(row.balanceAfter()),
                    row.bookingId());
        }
    }
}
