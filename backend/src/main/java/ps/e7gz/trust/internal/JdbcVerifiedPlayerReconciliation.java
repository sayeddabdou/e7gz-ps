package ps.e7gz.trust.internal;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.VerifiedPlayerStatus;
import ps.e7gz.common.codes.VerifiedPlayerTransitionCause;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.trust.api.TrustReconciler.VerifiedPlayerChange;

/**
 * E7-72's automatic Verified Player transitions, applied by E7-70's reconciler on the reconciling command's own
 * transaction (arch 07 sections 4 and 6.2; Spec v5 sections 8.4-8.7). The reconciler decides WHETHER a change is due
 * from the replayed qualification and the latest transition's stored {@code cause} column (never a guess from history);
 * this decides the transition row:
 * <ul>
 *   <li>VERIFY from NOT_VERIFIED - {@code TRUST_QUALIFICATION} when the command reached the threshold through normal
 *       live processing (section 8.4: it appended only live attendance outcomes), otherwise {@code TRUST_CORRECTION}
 *       (section 8.5: a correction discovered it). Activated now either way (FR-ACC-13);</li>
 *   <li>VERIFY from REVOKED - automatic regain, only when that revocation's cause was {@code TRUST_CORRECTION}
 *       (section 8.7, FR-ACC-14), {@code TRUST_CORRECTION};</li>
 *   <li>REVOKE_BY_CORRECTION - VERIFIED -> REVOKED, {@code TRUST_CORRECTION}, with its Tournament cascade once
 *       (sections 8.6 and 8.9).</li>
 * </ul>
 * Admin SUSPENDED and admin REVOKED are never touched (FR-ACC-15): the reconciler does not ask for them, and a request
 * that contradicts the current status is refused here rather than guessed.
 */
@Component
class JdbcVerifiedPlayerReconciliation implements VerifiedPlayerReconciliation {

    /**
     * Section 8.4 "normal live processing": the Trust events this command appended for the User (they carry the command
     * instant as {@code occurred_at}) are all live attendance outcomes - at least one, and no correction among them.
     */
    static final String LIVE_ONLY = """
            SELECT count(*) > 0 AND bool_and(e.event_type IN ('BOOKING_ATTENDED', 'BOOKING_ATTENDANCE_UNCONFIRMED',
                                                              'BOOKING_ATTENDED_INTERRUPTED', 'BOOKING_NO_SHOW'))
              FROM trust.trust_event e
             WHERE e.subject_user_id = :user AND e.occurred_at = trust.command_now(:user)
            """;

    private final VerifiedPlayerTransitions transitions;

    JdbcVerifiedPlayerReconciliation(VerifiedPlayerTransitions transitions) {
        this.transitions = transitions;
    }

    @Override
    public void apply(CommandContext command, UUID userId, VerifiedPlayerChange change) {
        Objects.requireNonNull(change, "change");
        Optional<VerifiedPlayerTransitions.Latest> latest = transitions.latest(command, userId);
        VerifiedPlayerStatus from = latest.map(VerifiedPlayerTransitions.Latest::status)
                .orElse(VerifiedPlayerStatus.NOT_VERIFIED);
        switch (change) {
            case VERIFY -> {
                VerifiedPlayerTransitionCause cause;
                if (from == VerifiedPlayerStatus.NOT_VERIFIED) {
                    cause = liveOnly(command, userId)
                            ? VerifiedPlayerTransitionCause.TRUST_QUALIFICATION
                            : VerifiedPlayerTransitionCause.TRUST_CORRECTION;
                } else if (from == VerifiedPlayerStatus.REVOKED
                        && latest.get().cause() == VerifiedPlayerTransitionCause.TRUST_CORRECTION) {
                    cause = VerifiedPlayerTransitionCause.TRUST_CORRECTION;
                } else {
                    throw new IllegalStateException("No automatic VERIFIED for user " + userId + " from " + from
                            + " (" + latest.map(VerifiedPlayerTransitions.Latest::cause).orElse(null) + ")");
                }
                transitions.record(command, userId, from, VerifiedPlayerStatus.VERIFIED, cause, null);
            }
            case REVOKE_BY_CORRECTION -> {
                if (from != VerifiedPlayerStatus.VERIFIED) {
                    throw new IllegalStateException("No correction revocation for user " + userId + " from " + from);
                }
                transitions.record(command, userId, from, VerifiedPlayerStatus.REVOKED,
                        VerifiedPlayerTransitionCause.TRUST_CORRECTION, null);
            }
        }
    }

    private static boolean liveOnly(CommandContext command, UUID userId) {
        return Boolean.TRUE.equals(command.jdbc().sql(LIVE_ONLY).param("user", userId).query(Boolean.class).single());
    }
}
