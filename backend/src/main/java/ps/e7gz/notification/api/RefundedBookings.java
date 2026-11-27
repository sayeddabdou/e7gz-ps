package ps.e7gz.notification.api;

import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: the booking whose Booking Fee refund gave Points back (E7-80 refund confirmation, PO decisions 25 Nov
 * 2026 R1/R5). A BOOKING_FEE_REFUND Points transaction carries no booking itself ({@code ck_points_tx_origin}); its
 * refund decision does. Implemented by the loyalty module; the interface lives here, as {@link NotifiedBookings}
 * does. Runs on the caller's command transaction.
 */
public interface RefundedBookings {

    /** The refunded booking of a BOOKING_FEE_REFUND Points transaction; empty when no refund decision names it. */
    Optional<UUID> bookingOfPointsRefund(CommandContext command, UUID pointsTransactionId);
}
