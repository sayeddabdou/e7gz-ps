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
    TOURNAMENT_PARTICIPANT_JOINED("TOURNAMENT_PARTICIPANT", AuditPayloadAllowlist.of("tournamentId"));

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
