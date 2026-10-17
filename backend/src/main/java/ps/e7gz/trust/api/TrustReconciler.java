package ps.e7gz.trust.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.VerifiedPlayerQualification;
import ps.e7gz.common.command.CommandContext;

/**
 * The reconciler of arch 07 section 4 (E7-70): after a command has appended Trust events for a User, it compares the
 * canonical replay ({@link TrustReplay}, the database fold) with what is stored - the Trust Projection, the consumed Ban
 * Qualifications, the operational Bans and the Verified Player transitions - and hands over ONLY what is newly derived.
 * Replay itself stays pure; the reconciler never re-applies a consequence that already exists, because every
 * consequence is guarded by its own key in the database (arch 07 section 4.1, NFR-06):
 * <ul>
 *   <li>Ban cancellation cascade - {@code ban_instance_id}, {@code cascade_executed_at} set once (FR-TRS-21; E7-71);</li>
 *   <li>Verified Player tournament cascade - {@code transition_id} (FR-TRN-20; E7-72);</li>
 *   <li>Attendance Protection reset - {@code booking_id}, {@code ux_trust_booking_account_disable_protection}
 *       (FR-ATT-22; written by its producer, never by replay).</li>
 * </ul>
 * Reconciling twice - in the same command or after it committed - therefore creates nothing twice. The database stays
 * the final authority ({@code trust.assert_user_integrity} at commit): a consequence the reconciler cannot apply rolls
 * the whole command back rather than committing a half-reconciled history.
 */
public interface TrustReconciler {

    /**
     * Reconciles the User on the command's transaction. The command must name the User in its lock set, so
     * {@code begin_command} has locked them with every Booking ({@code trust.command_now}). Recomputes the projection
     * only when it differs from the canonical replay or is stale, then hands outstanding Ban and Verified Player work to
     * their owners. Refuses with {@link TrustConsequenceUnavailableException} when such work exists and its owner is not
     * yet deployed (E7-71 bans, E7-72 Verified Player) - nothing is suppressed, fabricated or left for commit to find.
     */
    Reconciliation reconcile(CommandContext command, UUID userId);

    /**
     * A canonical replay threshold crossing ({@code trust.current_qualifications}) that no operational Ban consequence
     * has consumed yet (FR-TRS-17, FR-TRS-18). {@code anchorEventId} is the original attendance outcome the crossing
     * belongs to ({@code trust.qualification_anchor}), so an equivalent replacement correction is never punished twice.
     * {@code qualificationAt} is historical; an instance created for it activates now, never back-dated.
     */
    record OutstandingQualification(UUID effectEventId, UUID anchorEventId, Instant qualificationAt,
                                    long eventSequence, int penaltyStage) {

        public OutstandingQualification {
            Objects.requireNonNull(effectEventId, "effectEventId");
            Objects.requireNonNull(anchorEventId, "anchorEventId");
            Objects.requireNonNull(qualificationAt, "qualificationAt");
        }
    }

    /**
     * An ACTIVE Ban in force now whose remaining replay-valid support no longer covers its end: a correction removed or
     * shortened the qualifications it consumed (FR-TRS-24, FR-TRS-25). {@code supportedUntil} is null when no consumed
     * qualification is still current. E7-71 corrects or shortens it; previously cancelled bookings never return.
     */
    record UnsupportedBan(UUID banInstanceId, Instant endsAt, Instant supportedUntil) {

        public UnsupportedBan {
            Objects.requireNonNull(banInstanceId, "banInstanceId");
            Objects.requireNonNull(endsAt, "endsAt");
        }
    }

    /**
     * The Verified Player transition the replayed qualification requires (FR-ACC-11, arch 07 section 6): an initial or
     * regained {@code VERIFIED}, or a {@code TRUST_CORRECTION} revocation. Admin SUSPENDED/REVOKED are never touched.
     */
    enum VerifiedPlayerChange {
        VERIFY,
        REVOKE_BY_CORRECTION
    }

    /**
     * What one reconciliation found. {@code projectionRefreshed} is false when the stored projection already equalled
     * the replay; the lists are in canonical replay order; {@code verifiedPlayerChange} is null when none is due.
     */
    record Reconciliation(boolean projectionRefreshed, VerifiedPlayerQualification vpQualification,
                          List<OutstandingQualification> outstandingQualifications,
                          List<UnsupportedBan> unsupportedBans, VerifiedPlayerChange verifiedPlayerChange) {

        public Reconciliation {
            Objects.requireNonNull(vpQualification, "vpQualification");
            outstandingQualifications = List.copyOf(outstandingQualifications);
            unsupportedBans = List.copyOf(unsupportedBans);
        }

        /** Nothing newly derived: no Ban or Verified Player work is due. */
        public boolean settled() {
            return outstandingQualifications.isEmpty() && unsupportedBans.isEmpty() && verifiedPlayerChange == null;
        }
    }
}
