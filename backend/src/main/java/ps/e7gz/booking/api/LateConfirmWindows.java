package ps.e7gz.booking.api;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * The open Late Confirmation deadlines of bookings, for Booking's own projections (Sprint 3 contract, E7-54:
 * {@code CustomerBookingDetail.lateConfirm.expiresAt}, {@code VenueBooking.lateConfirmExpiresAt}). Declared here and
 * implemented by the Late Confirmation module, which owns {@code booking.late_confirmation} (arch 03 section 12), so
 * Booking reads the table only through it and never depends on that module (E7-58 Phase 2C). The pause flag is
 * Booking's own column ({@code late_confirm_pause_started_at}) and is not here.
 */
public interface LateConfirmWindows {

    /**
     * The stored deadline ({@code expires_at}) of each booking's open attempt, in one query on the command's
     * transaction - a page of bookings costs one read, not one per row. A booking with no open attempt is absent.
     */
    Map<UUID, Instant> openDeadlines(CommandContext command, Collection<UUID> bookingIds);
}
