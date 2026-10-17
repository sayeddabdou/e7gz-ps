package ps.e7gz.trust.internal;

import java.util.List;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.trust.api.TrustReconciler.OutstandingQualification;
import ps.e7gz.trust.api.TrustReconciler.UnsupportedBan;

/**
 * The integration boundary E7-71 implements (arch 07 sections 4, 5.2-5.6): the operational Ban consequence of what
 * {@link JdbcTrustReconciler} found newly derived, on the reconciling command's transaction. Not implemented yet - until
 * a bean exists, the reconciler refuses such work ({@code TrustConsequenceUnavailableException}).
 *
 * <p>The implementation must, in order and exactly once per key:
 * <ul>
 *   <li>for each outstanding qualification (canonical order; {@code trust.require_next_qualification} enforces it):
 *       write its {@code ban_qualification}, then create a Ban Instance activated now (no ACTIVE ban;
 *       {@code consume_ban_qualification(...,'CREATE',...)}) with its cascade once, guarded by
 *       {@code cascade_executed_at} - or extend the ACTIVE one ({@code 'EXTEND'}, MAX end, BAN_EXTENDED, cascade
 *       untouched; FR-TRS-22);</li>
 *   <li>for each unsupported Ban: correct or shorten it (FR-TRS-24/25), never returning cancelled bookings.</li>
 * </ul>
 * The reconciler re-checks afterwards and refreshes the projection's Ban pointer; work left over is an error.
 */
interface BanReconciliation {

    void reconcile(CommandContext command, UUID userId, List<OutstandingQualification> outstanding,
                   List<UnsupportedBan> unsupported);
}
