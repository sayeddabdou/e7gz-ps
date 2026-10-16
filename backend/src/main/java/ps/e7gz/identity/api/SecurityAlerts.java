package ps.e7gz.identity.api;

import java.util.UUID;
import ps.e7gz.common.codes.AuthSubjectType;

/**
 * Security events Identity reports to the Platform Admin (E7-56). Implemented by the Platform Administration module,
 * which already depends on Identity: the port lives here so Identity never depends on Administration (no module
 * cycle). Implementations never throw: a failing alert is logged, and the caller's answer is unchanged.
 */
public interface SecurityAlerts {

    /**
     * arch 10 section 2.3 ("revokes the entire chain and alerts"; Sprint 0 review S-9): a rotated refresh token was
     * presented again. The chain is already revoked and committed when this is called.
     */
    void refreshTokenReused(AuthSubjectType audience, UUID subjectId);

    /**
     * The per-account login limit (Sprint 0 review S-7, PO decision 15 Oct 2026; E7-57): this account's 50th failed
     * login in an hour paused its login for 15 minutes. Called once per pause, after the pause committed.
     */
    void accountLoginLimitReached(AuthSubjectType audience, UUID accountId);
}
