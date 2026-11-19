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
     * <p>E7-71: when the command already opened its Loyalty context - an Admin correction whose replay activated a Ban,
     * {@link BookingRewards#beginCorrection} - the batch joins it instead of opening a second one; its Customers must
     * be that context's.
     *
     * <p>An empty list returns an empty map without SQL. A null list, a null element and a repeated booking id are
     * refused before any SQL.
     *
     * @return an unmodifiable map of booking id to refund decision id, one entry per decision
     */
    Map<UUID, UUID> recordDecisions(CommandContext command, List<Decision> decisions);

    /**
     * E7-70: the fee decision of a Platform Admin no-show reversal (FRS reason map: {@code ADMIN_NO_SHOW_REVERSAL} is
     * FULL only if the wrongful no-show caused service loss, otherwise no refund from attendance correction alone;
     * FR-PTS-15). Opens the Loyalty command with the Admin ({@code loyalty.begin_admin_command}), records the Admin's
     * explicit, immutable assessment for {@code historyId} ({@code loyalty.assess_service_loss}: confirmed or not
     * established, with {@code reason}) and the decision resting on it ({@code loyalty.record_refund}), which derives
     * the amount. {@code historyId} is the CANCELLED / ADMIN_NO_SHOW_REVERSAL transition, or the
     * {@code NO_SHOW_REVERSED} Booking Event of a reversal after endAt. A later
     * {@link BookingRewards#reconcileCorrection} by the same Admin shares this Loyalty command. Returns the decision id.
     */
    UUID recordAdminDecision(CommandContext command, UUID adminId, UUID customerId, UUID bookingId, UUID historyId,
                             boolean serviceLoss, String reason);

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
