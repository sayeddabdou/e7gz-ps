package ps.e7gz.trust.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.TrustEventType;
import ps.e7gz.common.codes.VerifiedPlayerQualification;
import ps.e7gz.common.command.CommandContext;

/**
 * Pure corrected Trust history (E7-69; arch 07 section 3, arch 04 section 4.5). Reads the canonical database fold;
 * never refreshes a projection or creates rewards, bans, transitions or notifications. Operational bans and
 * Verified Player privileges must be read from their operational history, not inferred from qualification.
 * Each call is one database statement/snapshot. Command overloads read that command's own uncommitted inputs,
 * including on the admin connection; callers own locking and any subsequent reconciliation (E7-70/E7-71).
 */
public interface TrustReplay {
    /** Inclusive effective-time/sequence boundary (FR-TRS-06/10), not an occurred-at/knowledge-time filter.
     * Later corrections can therefore change an earlier answer. Null boundary means all stored inputs.
     */
    record Boundary(Instant effectiveAt, long eventSequence) {
        public Boundary {
            Objects.requireNonNull(effectiveAt, "effectiveAt");
            if (eventSequence < 0) {
                throw new IllegalArgumentException("eventSequence must be non-negative");
            }
        }

        /** Includes every sequence at this instant; use the constructor to stop inside an equal-time group. */
        public static Boundary at(Instant effectiveAt) {
            return new Boundary(effectiveAt, Long.MAX_VALUE);
        }
    }

    /** Last position is the source-stream watermark, including neutralised inputs; null for empty history. */
    record State(Instant lastEffectiveAt, Long lastEventSequence, int consecutiveNoShows, int penaltyStage,
                 int currentThreshold, int completedAttendedCount, VerifiedPlayerQualification vpQualification) { }

    /** One canonical contribution; correction identity/sequence at the original booking's effective time.
     * Payload is immutable JSON, including the frozen absolute admin-adjustment schema.
     */
    record Effect(UUID eventId, Instant effectiveAt, long eventSequence, TrustEventType type, String payload) { }

    /** State after one canonical contribution. qualificationStage is null except at a threshold crossing;
     * when present it is the resulting stage (1..3), not an operational ban or authorization to create one.
     */
    record Step(UUID eventId, Instant effectiveAt, long eventSequence, int consecutiveNoShows, int penaltyStage,
                int currentThreshold, int completedAttendedCount, VerifiedPlayerQualification vpQualification,
                Integer qualificationStage) { }

    State replay(UUID userId, Boundary boundary);

    State replay(CommandContext command, UUID userId, Boundary boundary);

    List<Effect> resolveEffectiveOutcomes(UUID userId, Boundary boundary);

    List<Effect> resolveEffectiveOutcomes(CommandContext command, UUID userId, Boundary boundary);

    List<Step> timeline(UUID userId, Boundary boundary);

    List<Step> timeline(CommandContext command, UUID userId, Boundary boundary);
}
