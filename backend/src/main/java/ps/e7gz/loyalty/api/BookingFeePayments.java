package ps.e7gz.loyalty.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.money.Money;

/**
 * Points and the Wallet pay a new Online Booking's Booking Fee (PCR-009, PCR-010; Product Owner decisions 24 and 25 Oct
 * 2026). Automatic, in the transaction that creates the booking: Points first - one fee unit
 * ({@code loyalty.points_per_fee_unit}, 10,000 at launch, even) pays one hour of fee in PER_HOUR mode (a half hour costs
 * half a unit) or the whole fee in PER_BOOKING mode (fewer Points pay nothing); then the Wallet pays what is left when
 * it holds all of it; anything still left stays uncollected until a payment provider exists (ADR-018). Never a partial
 * Wallet payment, never the venue gaming charge.
 *
 * <p>The ledger rows are producer-only (ADR-026): {@code loyalty.pay_booking_fee} writes the Points debit with its
 * {@code booking_fee_points_payment} row, and the Wallet debit with the booking's WALLET collection receipt. A later
 * refund gives the Points back with the refund decision and settles the money part into the Wallet
 * ({@code loyalty.record_refund}); the database decides both, so this code re-checks neither.
 */
public interface BookingFeePayments {

    /**
     * Opens the Loyalty command context for the Customer ({@code loyalty.begin_command}, inside the command's shared
     * context - the Customer must be in its lock set) and pays the fee of {@code bookingId} with Points, then the
     * Wallet, as far as they cover it. Call it from the command that creates the booking, once, as that command's only
     * Loyalty entry point. Returns how the fee was paid.
     */
    FeePayment pay(CommandContext command, UUID customerId, UUID bookingId);

    /**
     * The quote (PCR-010 item 6): how a booking of {@code nominalMinutes} created at {@code at} would pay its fee with
     * the Customer's balances now. Reads only, holds nothing; the booking itself uses its balances at creation.
     */
    FeePayment preview(CommandContext command, UUID customerId, int nominalMinutes, Instant at);

    /** How the fee of {@code bookingId} was paid at its creation. */
    FeePayment paid(CommandContext command, UUID bookingId);

    /**
     * @param pointsUsed   the Points spent on the fee
     * @param paidByPoints the part of the fee the Points paid
     * @param paidByWallet the part the Wallet paid (all of what Points left, or nothing)
     * @param leftToPay    the rest, collected by the payment provider (ADR-018)
     */
    record FeePayment(int pointsUsed, Money paidByPoints, Money paidByWallet, Money leftToPay) {

        public FeePayment {
            Objects.requireNonNull(paidByPoints, "paidByPoints");
            Objects.requireNonNull(paidByWallet, "paidByWallet");
            Objects.requireNonNull(leftToPay, "leftToPay");
        }

        /** Nothing paid by Points or the Wallet: all of {@code fee} is left to pay (EGP 0 at launch). */
        public static FeePayment unpaid(Money fee) {
            return new FeePayment(0, Money.ZERO, Money.ZERO, fee);
        }
    }
}
