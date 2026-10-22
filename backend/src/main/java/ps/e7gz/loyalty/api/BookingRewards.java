package ps.e7gz.loyalty.api;

import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * The reward reconciliation every canonical Booking Trust outcome needs (FR-PTS, arch 07 section 8): the database
 * refuses to commit a {@code BOOKING_*} Trust outcome without its {@code loyalty.reward_application} row in the same
 * transaction ({@code loyalty.assert_booking}: "Canonical Booking outcome/correction requires final Loyalty
 * reconciliation in the same transaction"). The row is producer-only (ADR-026): it is written only by
 * {@code loyalty.reconcile_booking}, which derives the entitlement from the outcome - zero for
 * {@code BOOKING_NO_SHOW}, so no points move. Added for E7-59 (the no-show's {@code BOOKING_NO_SHOW}).
 */
public interface BookingRewards {

    /**
     * Opens the Loyalty command context for the Customer ({@code loyalty.begin_command}, inside the command's shared
     * context - the Customer must be in its lock set) and reconciles the booking's reward against its current canonical
     * Trust outcome, written earlier in the same command. Returns the reward application id. Call it once per command,
     * as that command's only Loyalty entry point.
     */
    UUID reconcile(CommandContext command, UUID customerId, UUID bookingId);
}
