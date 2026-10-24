package ps.e7gz.loyalty.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.money.Money;

/**
 * Read-only: a Customer's points and wallet balances ({@code loyalty.points_account}, {@code loyalty.wallet_account}),
 * for the Platform Admin's user page (E7-66, PO decision D4, 17 Oct 2026; FR-ADM-04). The points balance is never
 * negative ({@code ck_points_balance_nonnegative}); the wallet holds Booking Fee refunds only (FR-PTS-10).
 */
public interface LoyaltyBalances {

    /** Empty when the user holds no loyalty accounts (not a Customer). */
    Optional<Balances> of(UUID userId);

    record Balances(int points, Money wallet) {

        public Balances {
            Objects.requireNonNull(wallet, "wallet");
        }
    }
}
