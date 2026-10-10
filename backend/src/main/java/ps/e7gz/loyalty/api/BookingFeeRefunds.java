package ps.e7gz.loyalty.api;

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
}
