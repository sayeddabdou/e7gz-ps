package ps.e7gz.tournament.internal;

import ps.e7gz.audit.api.AuditAction;
import ps.e7gz.audit.api.AuditPayloadAllowlist;

/**
 * Audit actions owned by Tournament. The constant name is the persisted {@code audit_event.action}; the database reads
 * both as evidence at {@code COMMIT} (arch 04 section 9.10), so the names are the physical model's, not ours to choose.
 */
enum TournamentAuditAction implements AuditAction {

    /**
     * One per {@code tournament_history} row, same instant, actor and correlation ID
     * ({@code tournament.assert_tournament_history_chain}); the row's previous / new state carry the transition.
     */
    TOURNAMENT_STATUS_TRANSITION("TOURNAMENT", AuditPayloadAllowlist.of("terminalReason")),

    /**
     * A Customer joined, subject the participant row, at its {@code status_changed_at}
     * ({@code tournament.assert_participant_audit}). E7-73 writes it only for the participating Player Creator.
     */
    TOURNAMENT_PARTICIPANT_JOINED("TOURNAMENT_PARTICIPANT", AuditPayloadAllowlist.of("tournamentId")),

    /** E7-93: the Participant left, by themselves (CUSTOMER), before {@code startAt}. */
    TOURNAMENT_PARTICIPANT_LEFT("TOURNAMENT_PARTICIPANT", AuditPayloadAllowlist.of("tournamentId")),

    /** E7-93: the Organizer removed the Participant - the Player Creator (CUSTOMER) of a Player-hosted Tournament. */
    TOURNAMENT_PARTICIPANT_REMOVED("TOURNAMENT_PARTICIPANT", AuditPayloadAllowlist.of("tournamentId")),

    /** E7-93: {@code INELIGIBLE} with its cause, by SYSTEM - a Ban came into force (O14, ADR-023). */
    TOURNAMENT_PARTICIPANT_INELIGIBLE("TOURNAMENT_PARTICIPANT",
            AuditPayloadAllowlist.of("tournamentId", "ineligibleReason")),

    /** E7-93: {@code INELIGIBLE/BAN -> JOINED} by SYSTEM - the Ban ended before {@code startAt} (FR-TRN-26). */
    TOURNAMENT_PARTICIPANT_RESTORED("TOURNAMENT_PARTICIPANT", AuditPayloadAllowlist.of("tournamentId", "asOf"));

    private final String subjectType;
    private final AuditPayloadAllowlist payloadAllowlist;

    TournamentAuditAction(String subjectType, AuditPayloadAllowlist payloadAllowlist) {
        this.subjectType = subjectType;
        this.payloadAllowlist = payloadAllowlist;
    }

    @Override
    public String subjectType() {
        return subjectType;
    }

    @Override
    public AuditPayloadAllowlist payloadAllowlist() {
        return payloadAllowlist;
    }
}
