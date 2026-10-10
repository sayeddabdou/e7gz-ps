package ps.e7gz.notification.api;

import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * The Venue Dashboard's Web Push subscriptions as other modules need them (ADR-014, arch 01 section 14 A6).
 */
public interface VenueBrowserPush {

    /**
     * Revokes every active subscription of the venue account in {@code context}'s transaction ({@code revoked_at} set,
     * never deleted, arch 08 section 4): called when a Platform Admin disables the account (FR-VEN-08; PO decision
     * 10 Oct 2026, E7-44 review). After a re-enable the Dashboard subscribes again when it is next opened. Returns how
     * many were revoked.
     */
    int revokeAll(CommandContext context, UUID venueAccountId);
}
