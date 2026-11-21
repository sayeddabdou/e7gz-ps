package ps.e7gz.loyalty.api;

import java.util.Map;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * The reward reconciliation every canonical Booking Trust outcome needs (FR-PTS, arch 07 section 8): the database
 * refuses to commit a {@code BOOKING_*} Trust outcome without its {@code loyalty.reward_application} row in the same
 * transaction ({@code loyalty.assert_booking}: "Canonical Booking outcome/correction requires final Loyalty
 * reconciliation in the same transaction"). The row is producer-only (ADR-026): it is written only by
 * {@code loyalty.reconcile_booking}, which derives the entitlement from the outcome - the attended reward for
 * {@code BOOKING_ATTENDED}, zero for {@code BOOKING_NO_SHOW} and {@code BOOKING_ATTENDANCE_UNCONFIRMED}, so no points
 * move. Added for E7-59 (the no-show's {@code BOOKING_NO_SHOW}); used by E7-61's completion.
 */
public interface BookingRewards {

    /**
     * Opens the Loyalty command context for the Customer ({@code loyalty.begin_command}, inside the command's shared
     * context - the Customer must be in its lock set) and reconciles the booking's reward against its current canonical
     * Trust outcome, written earlier in the same command. Returns the reward application id. Call it once per command,
     * as that command's only Loyalty entry point.
     */
    UUID reconcile(CommandContext command, UUID customerId, UUID bookingId);

    /**
     * E7-70: the same reconciliation after a Platform Admin attendance correction ({@code ATTENDANCE_CORRECTED}, written
     * earlier in the same command with its audited reason). {@code loyalty.reconcile_booking} grants or compensates the
     * reward from the booking's original completion configuration (FR-TRS-08, FR-PTS-03) and requires the correcting
     * Admin in the Loyalty command, so this opens it with {@code loyalty.begin_admin_command}. Once per command, the
     * Customer in its lock set; the only other Loyalty entry point it may share the command with is a
     * {@link BookingFeeRefunds#recordAdminDecision} by the same Admin and Customer (the same immutable context).
     */
    UUID reconcileCorrection(CommandContext command, UUID adminId, UUID customerId, UUID bookingId);

    /**
     * E7-71: opens the correcting Admin's Loyalty command ({@code loyalty.begin_admin_command}) before the command's
     * Trust write, so a Ban that the corrected replay activates records its cascade's fee decisions in it
     * ({@link BookingFeeRefunds#recordDecisions} joins it). {@link #reconcileCorrection} and
     * {@link BookingFeeRefunds#recordAdminDecision} by the same Admin and Customer then reuse the same context.
     */
    void beginCorrection(CommandContext command, UUID adminId, UUID customerId);

    /**
     * E7-82: the reconciliations of one command that ends several Customers' Online Bookings with a canonical Trust
     * outcome at once - an Emergency interrupting checked-in ACTIVE bookings ({@code BOOKING_ATTENDED_INTERRUPTED}: no
     * points, FR-TRS-11). One immutable Loyalty context per transaction ({@code loyalty.start_command}): when the command
     * already opened it - {@link BookingFeeRefunds#recordDecisions} for the same cascade, whose Customers must cover
     * these - this joins it; otherwise it opens it for these Customers. Each booking is reconciled with
     * {@code loyalty.reconcile_booking}, in Customer then booking order. An empty list does nothing.
     *
     * @param bookings booking id to its Customer
     */
    void reconcileAll(CommandContext command, Map<UUID, UUID> bookings);
}
