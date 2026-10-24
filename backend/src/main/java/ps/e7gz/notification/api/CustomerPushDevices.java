package ps.e7gz.notification.api;

import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * The customer app's push devices as other modules need them (arch 08 section 4).
 */
public interface CustomerPushDevices {

    /**
     * Revokes every active device token of the customer in {@code context}'s transaction ({@code revoked_at} set,
     * never deleted, arch 08 section 4): called wherever every session of the account ends - a password reset, a
     * verification with a new password, a released unverified account (PO decision 24 Oct 2026, E7-119). The app
     * registers its device again after the next login. Returns how many were revoked.
     */
    int revokeAll(CommandContext context, UUID customerId);
}
