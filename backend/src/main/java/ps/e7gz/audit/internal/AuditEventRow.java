package ps.e7gz.audit.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.OwnerPasswordCheck;

/**
 * Exactly the values bound to the {@code audit.audit_event} INSERT. {@code payloadJson} is the allowlisted payload,
 * or {@code null} when nothing survives the allowlist.
 */
record AuditEventRow(UUID id, Instant occurredAt, String actorType, UUID actorId, String action, String subjectType,
                     UUID subjectId, String reason, Boolean ownerPasswordUsed, String ownerPasswordOutcome,
                     UUID correlationId, String payloadJson) {

    private static final ObjectMapper JSON = new ObjectMapper();

    static AuditEventRow of(UUID id, Instant commandNow, AuditRecord record) {
        OwnerPasswordCheck check = record.ownerPasswordCheck().orElse(null);
        Map<String, Object> payload = AuditPayloadFilter.filter(record.action().payloadAllowlist(), record.payload());
        return new AuditEventRow(
                id,
                commandNow,
                record.actor().actorType().name(),
                record.actor().actorId(),
                record.action().name(),
                record.action().subjectType(),
                record.subjectId(),
                record.reason().orElse(null),
                check == null ? null : Boolean.TRUE,
                check == null ? null : check.outcome().name(),
                record.correlationId(),
                payload.isEmpty() ? null : json(payload));
    }

    private static String json(Map<String, Object> payload) {
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Only strings, numbers and booleans reach this point; never include the payload in the message.
            throw new IllegalStateException("Audit payload could not be serialized", e);
        }
    }
}
