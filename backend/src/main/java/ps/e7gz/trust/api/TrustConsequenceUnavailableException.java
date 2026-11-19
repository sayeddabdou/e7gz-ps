package ps.e7gz.trust.api;

import java.util.UUID;

/**
 * A reconciliation found newly derived Trust work - a Ban Qualification to consume, a Ban that lost its support or a
 * Verified Player transition - whose operational owner is not deployed yet (E7-71 bans and their cascade, E7-72
 * Verified Player). Thrown on the command's thread so the whole command rolls back: the database would refuse the
 * commit anyway ({@code trust.assert_user_integrity}), and Trust never suppresses an event, fabricates a Ban or commits a
 * half-reconciled history (arch 07 section 4). A generic 500 to an HTTP caller; nothing is stored under an
 * Idempotency-Key.
 */
public final class TrustConsequenceUnavailableException extends IllegalStateException {

    private final UUID userId;
    private final transient TrustReconciler.Reconciliation reconciliation;

    public TrustConsequenceUnavailableException(UUID userId, String owner,
                                                TrustReconciler.Reconciliation reconciliation) {
        super("Trust reconciliation of user " + userId + " needs " + owner + ", which is not available"
                + (reconciliation == null ? "" : ": " + reconciliation));
        this.userId = userId;
        this.reconciliation = reconciliation;
    }

    public UUID userId() {
        return userId;
    }

    public TrustReconciler.Reconciliation reconciliation() {
        return reconciliation;
    }
}
