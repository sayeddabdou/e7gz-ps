package ps.e7gz.trust.internal;

import java.util.UUID;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.trust.api.TrustReconciler.VerifiedPlayerChange;

/**
 * The integration boundary E7-72 implements (arch 07 sections 4 and 6.2): the automatic Verified Player transition the
 * replayed qualification requires, as one {@code verified_player_transition} row ({@code transition_id} UNIQUE) with its
 * tournament cascade once (FR-TRN-20), on the reconciling command's transaction. Not implemented yet - until a bean
 * exists, the reconciler refuses such work ({@code TrustConsequenceUnavailableException}).
 */
interface VerifiedPlayerReconciliation {

    void apply(CommandContext command, UUID userId, VerifiedPlayerChange change);
}
