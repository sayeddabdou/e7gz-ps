package ps.e7gz.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ps.e7gz.audit.TestAuditAction;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.OwnerPasswordCheck;

/** The exact values bound to the INSERT, including the serialized payload. */
class AuditEventRowTest {

    private static final Instant NOW = Instant.parse("2026-09-30T10:15:30.123456Z");

    private static AuditEventRow row(AuditRecord record) {
        return AuditEventRow.of(UUID.randomUUID(), NOW, record);
    }

    @Test
    void secretsNeverReachTheSerializedInsertPayload() {
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("startAt", "2026-10-01T18:00:00Z");
        window.put("authorization", "Bearer leak");
        window.put("session", Map.of("cookie", "leak"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resourceName", "Room 1");
        payload.put("ownerPassword", "leak");
        payload.put("passcode", "leak");
        payload.put("clientSecret", "leak");
        payload.put("otp", "leak");
        payload.put("refreshToken", "leak");
        payload.put("apiKey", "leak");
        payload.put("privateKey", "leak");
        payload.put("window", window);
        payload.put("items", List.of(Map.of("id", "A", "token", "leak")));

        String json = row(AuditRecord.builder(TestAuditAction.E7_17_TEST_RESOURCE_NOTE, AuditActor.system(),
                UUID.randomUUID(), UUID.randomUUID()).payload(payload).build()).payloadJson();

        assertThat(json).isEqualTo(
                "{\"resourceName\":\"Room 1\",\"window\":{\"startAt\":\"2026-10-01T18:00:00Z\"},\"items\":[{\"id\":\"A\"}]}");
        assertThat(json.toLowerCase()).doesNotContain("leak", "password", "passcode", "secret", "otp", "token", "apikey",
                "privatekey", "authorization", "cookie", "bearer");
    }

    @Test
    void nothingAllowedMeansNoPayload() {
        assertThat(row(AuditRecord.builder(TestAuditAction.E7_17_TEST_NO_PAYLOAD, AuditActor.system(),
                UUID.randomUUID(), UUID.randomUUID()).payload(Map.of("resourceName", "Room 1")).build()).payloadJson())
                .isNull();
    }

    @Test
    void actorOwnerPasswordCorrelationAndTimeAreMappedAsGiven() {
        UUID venueAccount = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();

        AuditEventRow failed = row(AuditRecord.builder(TestAuditAction.E7_17_TEST_NO_PAYLOAD,
                AuditActor.venue(venueAccount), subject, correlation).ownerPasswordCheck(OwnerPasswordCheck.failed())
                .build());

        assertThat(failed.occurredAt()).isEqualTo(NOW);
        assertThat(failed.actorType()).isEqualTo("VENUE");
        assertThat(failed.actorId()).isEqualTo(venueAccount);
        assertThat(failed.action()).isEqualTo("E7_17_TEST_NO_PAYLOAD");
        assertThat(failed.subjectType()).isEqualTo("BOOKING");
        assertThat(failed.subjectId()).isEqualTo(subject);
        assertThat(failed.correlationId()).isEqualTo(correlation);
        assertThat(failed.ownerPasswordUsed()).isTrue();
        assertThat(failed.ownerPasswordOutcome()).isEqualTo("FAILURE");

        AuditEventRow none = row(AuditRecord.builder(TestAuditAction.E7_17_TEST_NO_PAYLOAD, AuditActor.system(),
                subject, correlation).build());
        assertThat(none.actorId()).isNull();
        assertThat(none.ownerPasswordUsed()).isNull();
        assertThat(none.ownerPasswordOutcome()).isNull();
    }
}
