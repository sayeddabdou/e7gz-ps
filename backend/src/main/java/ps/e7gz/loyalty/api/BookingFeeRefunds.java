package ps.e7gz.loyalty.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * The Booking Fee decision every terminal Online Booking needs (Product v5.0 §34.2, FR-PTS-13): the database refuses to
 * commit a REJECTED or CANCELLED Online Booking without its {@code loyalty.booking_fee_refund} row
 * ({@code loyalty.assert_booking}: "Terminal Booking fee decision omitted"). The row is producer-only (ADR-026): it is
 * written only by {@code loyalty.record_refund}, which derives the treatment and amount from the booking's own
 * terminal history row and settles a collected fee, if any. Added for E7-47 (venue reject, reject after expiry,
 * FR-BOK-16 cancellation).
 */
public interface BookingFeeRefunds {

    /**
     * Opens the Loyalty command context for the Customer ({@code loyalty.begin_command}, inside the command's shared
     * context - the Customer must be in its lock set) and records the decision for the terminal transition
     * {@code terminalHistoryId}. Returns the refund decision id. Call it once per command.
     */
    UUID recordDecision(CommandContext command, UUID customerId, UUID bookingId, UUID terminalHistoryId);

    /**
     * E7-113: the decisions of one command that ends Online Bookings of several Customers at once (an opening-hours
     * change cancels every affected booking of a venue). The database allows one immutable Loyalty command context per
     * transaction ({@code loyalty.start_command}), so {@link #recordDecision} cannot serve two Customers in one command;
     * this opens it once, for every Customer of the batch, and records each decision with {@code loyalty.record_refund}.
     *
     * <p>When a command uses this batch it is that command's only Loyalty entry point: no {@link #recordDecision} and no
     * second batch in the same command. Every Customer of the batch must already be in the command's user lock set
     * ({@code begin_command}; the database refuses otherwise: "All Loyalty Users must be prelocked in the shared
     * scope"). It runs on the caller's transaction - nothing commits here, and any database error propagates and rolls
     * the whole command back.
     *
     * <p>An empty list returns an empty map without SQL. A null list, a null element and a repeated booking id are
     * refused before any SQL.
     *
     * @return an unmodifiable map of booking id to refund decision id, one entry per decision
     */
    Map<UUID, UUID> recordDecisions(CommandContext command, List<Decision> decisions);

    /**
     * One terminal Online Booking of the batch: its Customer and the terminal history row its decision is based on.
     */
    record Decision(UUID customerId, UUID bookingId, UUID terminalHistoryId) {

        public Decision {
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(bookingId, "bookingId");
            Objects.requireNonNull(terminalHistoryId, "terminalHistoryId");
        }
    }
}
