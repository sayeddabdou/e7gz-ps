package ps.e7gz.loyalty.api;

import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * The Wallet pays a new Online Booking's Booking Fee (PCR-009, Product Owner decision 24 Oct 2026; E7-66 PR 3): the
 * Wallet holds only Booking Fee refunds (FR-PTS-10), and the customer spends them on a later booking. Automatic: when
 * the booking's stored fee is above zero and the Wallet holds at least that amount, the whole fee is paid from the
 * Wallet; otherwise nothing is paid and the fee stays uncollected, as before, until a payment provider exists
 * (ADR-018). Never a partial payment, never the venue gaming charge.
 *
 * <p>The ledger rows are producer-only (ADR-026): {@code loyalty.pay_fee_from_wallet} writes the Wallet debit and the
 * booking's WALLET collection receipt, so a later refund of that fee settles back into the Wallet through
 * {@code loyalty.record_refund}, unchanged.
 */
public interface BookingFeePayments {

    /**
     * Opens the Loyalty command context for the Customer ({@code loyalty.begin_command}, inside the command's shared
     * context - the Customer must be in its lock set) and pays the fee of {@code bookingId} from the Wallet when the
     * Wallet covers it. Call it from the command that creates the booking, once, as that command's only Loyalty entry
     * point. Returns the Wallet transaction, or empty when nothing was paid.
     */
    Optional<UUID> payFromWallet(CommandContext command, UUID customerId, UUID bookingId);
}
