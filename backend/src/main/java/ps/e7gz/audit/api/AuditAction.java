package ps.e7gz.audit.api;

/**
 * A typed audit action: the persisted {@code audit.audit_event.action} code, the subject type it applies to, and the
 * payload fields that are known to be safe for it.
 *
 * <p>Implement it with an <b>enum</b> in the module that owns the action - the enum constant name is the persisted
 * code, so feature code uses constants, never ad-hoc strings (an architecture test rejects non-enum implementations):
 * <pre>{@code
 * enum OperationsAuditAction implements AuditAction {
 *     SOME_ACTION("RESOURCE", AuditPayloadAllowlist.of("previousState", "window.startAt"));
 *     ...
 * }
 * }</pre>
 */
public interface AuditAction {

    /** The persisted action code: the enum constant name. */
    String name();

    /** {@code audit.audit_event.subject_type}; upper-case identifier ({@code ck_audit_subject_type_format}). */
    String subjectType();

    /** The only payload fields this action may persist. */
    AuditPayloadAllowlist payloadAllowlist();

    /** True where the Product requires a reason for this action (FR-AUD-06); the record is then refused without one. */
    default boolean requiresReason() {
        return false;
    }
}
