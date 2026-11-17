package ps.e7gz.trust.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * The Booking side of a Ban activation's cancellation cascade (arch 07 section 5.4, FR-TRS-20; E7-71). Declared here
 * and implemented by Booking, which owns {@code booking.booking}: Booking already depends on Trust ({@link ActiveBans}),
 * so Trust cannot call Booking directly (CLAUDE.md section 3: a port in the depending module's api).
 *
 * <p>Runs on the activating command's transaction, after {@code trust.register_ban_cascade_targets} captured the exact
 * target set: every one of {@code bookingIds} - the Customer's PENDING, AWAITING_CUSTOMER_CONFIRMATION and future
 * CONFIRMED Online Bookings - ends CANCELLED / {@code USER_BANNED} at {@code at} (the activation instant) with its
 * STATUS_TRANSITION history under the command's correlation, its hold released at {@code at} (an AWAITING one's
 * Late Confirmation closed), its jobs withdrawn, its Booking Fee decision and one {@code BookingCancelled}. ACTIVE
 * bookings are never targets: they continue. The database proves the exact set at commit
 * ({@code trust.assert_ban_target_completion}); a target that cannot be cancelled is a data defect and rolls the whole
 * command back.
 */
public interface BanCascadeBookings {

    void cancel(CommandContext command, UUID customerId, List<UUID> bookingIds, Instant at);
}
