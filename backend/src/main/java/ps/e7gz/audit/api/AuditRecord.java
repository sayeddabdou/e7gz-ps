package ps.e7gz.audit.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One audit record to write (FR-AUD-06, arch 10 section 8.2): what happened ({@link AuditAction}) to which subject, who
 * did it, why, and the correlation ID of the unit of work.
 *
 * <p>The correlation ID is supplied by the caller: the command edge reads {@code CorrelationId.require()} once and
 * passes it here. The audit module never creates one.
 *
 * <p>The payload is a candidate only: the writer keeps just the fields the action's {@link AuditPayloadAllowlist}
 * names. A blank reason counts as no reason.
 */
public final class AuditRecord {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final AuditAction action;
    private final AuditActor actor;
    private final UUID subjectId;
    private final UUID correlationId;
    private final String reason;
    private final Map<String, ?> payload;
    private final OwnerPasswordCheck ownerPasswordCheck;

    private AuditRecord(Builder builder) {
        this.action = builder.action;
        this.actor = builder.actor;
        this.subjectId = builder.subjectId;
        this.correlationId = builder.correlationId;
        this.reason = builder.reason;
        this.payload = builder.payload;
        this.ownerPasswordCheck = builder.ownerPasswordCheck;
    }

    public static Builder builder(AuditAction action, AuditActor actor, UUID subjectId, UUID correlationId) {
        return new Builder(action, actor, subjectId, correlationId);
    }

    public AuditAction action() {
        return action;
    }

    public AuditActor actor() {
        return actor;
    }

    public UUID subjectId() {
        return subjectId;
    }

    public UUID correlationId() {
        return correlationId;
    }

    /** The supplied reason, unchanged; empty when none (or blank) was supplied. */
    public Optional<String> reason() {
        return Optional.ofNullable(reason);
    }

    /** The candidate payload, before allowlisting; empty map when none was supplied. */
    public Map<String, ?> payload() {
        return payload;
    }

    public Optional<OwnerPasswordCheck> ownerPasswordCheck() {
        return Optional.ofNullable(ownerPasswordCheck);
    }

    public static final class Builder {

        private final AuditAction action;
        private final AuditActor actor;
        private final UUID subjectId;
        private final UUID correlationId;
        private String reason;
        private Map<String, ?> payload = Map.of();
        private OwnerPasswordCheck ownerPasswordCheck;

        private Builder(AuditAction action, AuditActor actor, UUID subjectId, UUID correlationId) {
            this.action = Objects.requireNonNull(action, "action");
            this.actor = Objects.requireNonNull(actor, "actor");
            this.subjectId = Objects.requireNonNull(subjectId, "subjectId");
            this.correlationId = Objects.requireNonNull(correlationId, "correlationId (pass CorrelationId.require())");
        }

        /** The reason as the actor gave it; blank means none. */
        public Builder reason(String reason) {
            this.reason = reason == null || reason.isBlank() ? null : reason;
            return this;
        }

        public Builder payload(Map<String, ?> payload) {
            this.payload = payload == null ? Map.of() : payload;
            return this;
        }

        public Builder ownerPasswordCheck(OwnerPasswordCheck check) {
            this.ownerPasswordCheck = check;
            return this;
        }

        public AuditRecord build() {
            if (!CODE.matcher(action.name()).matches()) {
                throw new IllegalArgumentException("Audit action code must be an upper-case identifier: " + action.name());
            }
            String subjectType = action.subjectType();
            if (subjectType == null || !CODE.matcher(subjectType).matches()) {
                throw new IllegalArgumentException(
                        "Audit subject type of " + action.name() + " must be an upper-case identifier: " + subjectType);
            }
            Objects.requireNonNull(action.payloadAllowlist(), "payloadAllowlist of " + action.name());
            if (action.requiresReason() && reason == null) {
                throw new IllegalArgumentException("Audit action " + action.name() + " requires a reason");
            }
            return new AuditRecord(this);
        }
    }
}
