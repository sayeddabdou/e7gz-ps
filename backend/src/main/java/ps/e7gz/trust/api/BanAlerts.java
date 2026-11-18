package ps.e7gz.trust.api;

import java.time.Instant;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * Ban events Trust reports to the Platform Admin (Product v5.0 section 52.6 "User Temporary Ban Activated"; E7-71, PO
 * decision 20 Oct 2026: the admin alert inbox only, no per-admin notification). Implemented by the Platform
 * Administration module, which already depends on Identity and through it on Trust: the port lives here so Trust never
 * depends on Administration (no module cycle; CLAUDE.md section 3, as {@code identity.api.SecurityAlerts}).
 */
public interface BanAlerts {

    /**
     * {@code ADMIN_USER_BAN_ACTIVATED}: a replay threshold crossing activated a Ban. Raised in the activating command, so
     * the alert commits or rolls back with the Ban.
     *
     * @param cancelledBookings how many bookings the Ban's cascade cancelled (FR-TRS-20)
     */
    void banActivated(CommandContext command, UUID userId, UUID banId, int penaltyStage, Instant activatedAt,
                      Instant endsAt, int cancelledBookings, UUID correlationId);
}
