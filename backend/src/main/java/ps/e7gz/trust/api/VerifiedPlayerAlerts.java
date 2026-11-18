package ps.e7gz.trust.api;

import java.util.UUID;
import ps.e7gz.common.codes.VerifiedPlayerTransitionCause;
import ps.e7gz.common.command.CommandContext;

/**
 * Verified Player events Trust reports to the Platform Admin (Spec v5 section 52.6 "Verified Player Revoked",
 * FR-NTF-10). Implemented by the Platform Administration module, which already depends (through Identity) on Trust: the
 * port lives here so Trust never depends on Administration (no module cycle). PO decision 19 Oct 2026 (E7-72
 * carry-forward): the revocation goes only to the admin alert inbox, as E7-71's ban alert does.
 */
public interface VerifiedPlayerAlerts {

    /**
     * A VERIFIED -> REVOKED transition, by an admin or by a trust correction, raised in the revoking command so it
     * commits or rolls back with the revocation.
     */
    void revoked(CommandContext command, UUID userId, UUID transitionId, VerifiedPlayerTransitionCause cause);
}
