package ps.e7gz.audit.archfixture;

import ps.e7gz.audit.api.AuditAction;
import ps.e7gz.audit.api.AuditPayloadAllowlist;

/** Positive control for {@code AuditArchitectureTest}: a non-enum action whose code is free text. Never used. */
public class FreeTextAuditAction implements AuditAction {

    private final String code;

    public FreeTextAuditAction(String code) {
        this.code = code;
    }

    @Override
    public String name() {
        return code;
    }

    @Override
    public String subjectType() {
        return "BOOKING";
    }

    @Override
    public AuditPayloadAllowlist payloadAllowlist() {
        return AuditPayloadAllowlist.none();
    }
}
