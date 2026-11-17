package ps.e7gz.trust.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.VerifiedPlayerQualification;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.trust.api.TrustConsequenceUnavailableException;
import ps.e7gz.trust.api.TrustReconciler;

/**
 * Arch 07 section 4 on the command's transaction (E7-70). Every comparison reads the canonical database fold
 * ({@code trust.replay_state}, {@code trust.current_qualifications}, {@code trust.qualification_anchor}) - no replay rule
 * is reproduced in Java - and mirrors the final validator {@code trust.assert_user_integrity}, which stays the authority
 * at commit. The projection is recomputed only when stale ({@code trust.refresh_projection}), so a second
 * reconciliation of a settled User writes nothing at all.
 */
@Component
class JdbcTrustReconciler implements TrustReconciler {

    /**
     * Whether the stored projection already equals the final replay values, its Ban pointer and its Verified Player
     * status at the command instant, and was recomputed after every event: exactly what
     * {@code trust.assert_user_integrity} requires. A User with no history and no projection has nothing to reconcile.
     */
    static final String PROJECTION = """
            WITH r AS (SELECT * FROM trust.replay_state(:user)),
                 cmd AS (SELECT trust.command_now(:user) AS at),
                 vp AS (SELECT x.to_status FROM trust.verified_player_transition x WHERE x.user_id = :user
                         ORDER BY x.occurred_at DESC, x.id DESC LIMIT 1),
                 ban AS (SELECT x.id FROM trust.ban_instance x, cmd
                          WHERE x.user_id = :user AND x.status = 'ACTIVE' AND x.activated_at <= cmd.at
                            AND x.ends_at > cmd.at)
            SELECT r.vp_qualification,
                   CASE WHEN p.user_id IS NULL THEN r.last_event_sequence IS NULL
                            AND NOT EXISTS (SELECT 1 FROM trust.verified_player_transition x WHERE x.user_id = :user)
                            AND NOT EXISTS (SELECT 1 FROM trust.ban_instance x WHERE x.user_id = :user)
                        ELSE p.last_effective_at IS NOT DISTINCT FROM r.last_effective_at
                            AND p.last_event_sequence IS NOT DISTINCT FROM r.last_event_sequence
                            AND p.consecutive_no_shows IS NOT DISTINCT FROM r.consecutive_no_shows
                            AND p.penalty_stage IS NOT DISTINCT FROM r.penalty_stage
                            AND p.current_threshold IS NOT DISTINCT FROM r.current_threshold
                            AND p.completed_attended_count IS NOT DISTINCT FROM r.completed_attended_count
                            AND p.vp_qualification IS NOT DISTINCT FROM r.vp_qualification
                            AND p.vp_operational_status IS NOT DISTINCT FROM
                                COALESCE((SELECT to_status FROM vp), 'NOT_VERIFIED')
                            AND p.active_ban_instance_id IS NOT DISTINCT FROM (SELECT id FROM ban)
                            AND p.recomputed_at IS NOT NULL
                            AND NOT EXISTS (SELECT 1 FROM trust.trust_event e
                                             WHERE e.subject_user_id = :user AND e.occurred_at > p.recomputed_at)
                   END AS current
              FROM r LEFT JOIN trust.trust_projection p ON p.user_id = :user
            """;

    static final String REFRESH = "SELECT trust.refresh_projection(:user)";

    /**
     * Canonical crossings with no consumed operational consequence - the same match {@code assert_user_integrity}
     * requires (stage, qualification instant and original anchor), in replay order.
     */
    static final String OUTSTANDING = """
            SELECT curr.effect_event_id, curr.anchor_id, curr.effective_at, curr.event_sequence,
                   curr.qualification_stage
              FROM trust.current_qualifications(:user) curr
             WHERE NOT EXISTS (
                   SELECT 1 FROM trust.ban_qualification_consumption c
                     JOIN trust.ban_qualification q ON q.id = c.qualification_id
                     JOIN trust.ban_instance bi ON bi.id = c.ban_instance_id
                    WHERE q.user_id = :user AND bi.user_id = :user
                      AND q.penalty_stage = curr.qualification_stage
                      AND q.qualification_at = curr.effective_at
                      AND trust.qualification_anchor(q.triggering_event_id) = curr.anchor_id)
             ORDER BY curr.effective_at, curr.event_sequence
            """;

    /**
     * ACTIVE Bans in force at the command instant whose consumed, still-current qualifications no longer cover their
     * end (FR-TRS-24/25): the two refusals of {@code assert_user_integrity} for a current restriction.
     */
    static final String UNSUPPORTED = """
            WITH cmd AS (SELECT trust.command_now(:user) AS at)
            SELECT b.id, b.ends_at, v.valid_until
              FROM trust.ban_instance b CROSS JOIN cmd
             CROSS JOIN LATERAL (
                   SELECT max(c.consumed_at + trust.penalty_duration(q.penalty_stage)) AS valid_until
                     FROM trust.ban_qualification_consumption c
                     JOIN trust.ban_qualification q ON q.id = c.qualification_id
                    WHERE c.ban_instance_id = b.id
                      AND EXISTS (SELECT 1 FROM trust.current_qualifications(:user) curr
                                   WHERE curr.anchor_id = trust.qualification_anchor(q.triggering_event_id)
                                     AND curr.effective_at = q.qualification_at
                                     AND curr.qualification_stage = q.penalty_stage)) v
             WHERE b.user_id = :user AND b.status = 'ACTIVE' AND b.activated_at <= cmd.at AND b.ends_at > cmd.at
               AND (v.valid_until IS NULL OR v.valid_until <= cmd.at OR b.ends_at > v.valid_until)
             ORDER BY b.activated_at, b.id
            """;

    /** The latest operational Verified Player transition, against which the replayed qualification is compared. */
    static final String VERIFIED_PLAYER = """
            SELECT x.to_status, x.cause FROM trust.verified_player_transition x
             WHERE x.user_id = :user ORDER BY x.occurred_at DESC, x.id DESC LIMIT 1
            """;

    private final ObjectProvider<BanReconciliation> bans;
    private final ObjectProvider<VerifiedPlayerReconciliation> verifiedPlayer;

    JdbcTrustReconciler(ObjectProvider<BanReconciliation> bans,
                        ObjectProvider<VerifiedPlayerReconciliation> verifiedPlayer) {
        this.bans = bans;
        this.verifiedPlayer = verifiedPlayer;
    }

    @Override
    public Reconciliation reconcile(CommandContext command, UUID userId) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(userId, "userId");
        boolean refreshed = refreshIfStale(command, userId);
        Reconciliation found = inspect(command, userId, refreshed);
        if (found.settled()) {
            return found;
        }
        if (!found.outstandingQualifications().isEmpty() || !found.unsupportedBans().isEmpty()) {
            BanReconciliation owner = bans.getIfAvailable();
            if (owner == null) {
                throw new TrustConsequenceUnavailableException(userId, "E7-71 Ban reconciliation", found);
            }
            owner.reconcile(command, userId, found.outstandingQualifications(), found.unsupportedBans());
        }
        if (found.verifiedPlayerChange() != null) {
            VerifiedPlayerReconciliation owner = verifiedPlayer.getIfAvailable();
            if (owner == null) {
                throw new TrustConsequenceUnavailableException(userId, "E7-72 Verified Player reconciliation", found);
            }
            owner.apply(command, userId, found.verifiedPlayerChange());
        }
        // The owners changed operational state (a Ban pointer, a Verified Player status): settle the projection on it
        refreshIfStale(command, userId);
        Reconciliation after = inspect(command, userId, true);
        if (!after.settled()) {
            throw new IllegalStateException("Trust reconciliation of user " + userId + " left work behind: " + after);
        }
        return found;
    }

    private static boolean refreshIfStale(CommandContext command, UUID userId) {
        boolean current = Boolean.TRUE.equals(command.jdbc().sql(PROJECTION).param("user", userId)
                .query((rs, n) -> rs.getBoolean("current")).single());
        if (current) {
            return false;
        }
        command.jdbc().sql(REFRESH).param("user", userId).query().singleRow();
        return true;
    }

    private static Reconciliation inspect(CommandContext command, UUID userId, boolean refreshed) {
        VerifiedPlayerQualification qualification = VerifiedPlayerQualification.valueOf(command.jdbc()
                .sql("SELECT vp_qualification FROM trust.replay_state(:user)").param("user", userId)
                .query(String.class).single());
        List<OutstandingQualification> outstanding = command.jdbc().sql(OUTSTANDING).param("user", userId)
                .query((rs, n) -> new OutstandingQualification(rs.getObject("effect_event_id", UUID.class),
                        rs.getObject("anchor_id", UUID.class), instant(rs, "effective_at"),
                        rs.getLong("event_sequence"), rs.getInt("qualification_stage")))
                .list();
        List<UnsupportedBan> unsupported = command.jdbc().sql(UNSUPPORTED).param("user", userId)
                .query((rs, n) -> new UnsupportedBan(rs.getObject("id", UUID.class), instant(rs, "ends_at"),
                        instant(rs, "valid_until")))
                .list();
        // Read the latest transition first, then decide: a mapper returning "no change" (null) must not read as "no
        // transition" (E7-72 found that the earlier optional().orElseGet re-asked VERIFY of a VERIFIED User).
        String[] latest = command.jdbc().sql(VERIFIED_PLAYER).param("user", userId)
                .query((rs, n) -> new String[] {rs.getString("to_status"), rs.getString("cause")})
                .optional()
                .orElse(new String[] {null, null});
        VerifiedPlayerChange change = verifiedPlayerChange(qualification, latest[0], latest[1]);
        return new Reconciliation(refreshed, qualification, outstanding, unsupported, change);
    }

    /**
     * The two automatic rules of {@code assert_user_integrity} (arch 07 section 6.2): a lost qualification revokes a
     * VERIFIED status by correction; a qualified User without status, or revoked only by correction, is verified.
     * Admin SUSPENDED and REVOKED are protected and never changed automatically (FR-ACC-15).
     */
    static VerifiedPlayerChange verifiedPlayerChange(VerifiedPlayerQualification qualification, String status,
                                                     String cause) {
        String current = status == null ? "NOT_VERIFIED" : status;
        if (qualification == VerifiedPlayerQualification.NOT_QUALIFIED && current.equals("VERIFIED")) {
            return VerifiedPlayerChange.REVOKE_BY_CORRECTION;
        }
        if (qualification == VerifiedPlayerQualification.QUALIFIED && (current.equals("NOT_VERIFIED")
                || (current.equals("REVOKED") && "TRUST_CORRECTION".equals(cause)))) {
            return VerifiedPlayerChange.VERIFY;
        }
        return null;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
