package ps.e7gz.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ps.e7gz.audit.api.AuditPayloadAllowlist;

class AuditPayloadFilterTest {

    private static final AuditPayloadAllowlist ALLOW = AuditPayloadAllowlist.of(
            "resourceName", "amount", "resourceId", "window.startAt", "bookingIds[]", "items[].id");

    @Test
    void anAllowedFieldIsKept() {
        assertThat(AuditPayloadFilter.filter(ALLOW, Map.of("resourceName", "Room 1")))
                .containsExactly(Map.entry("resourceName", "Room 1"));
    }

    @Test
    void anUnknownHarmlessFieldIsDropped() {
        assertThat(AuditPayloadFilter.filter(ALLOW, Map.of("resourceName", "Room 1", "colour", "blue")))
                .containsOnlyKeys("resourceName");
    }

    @Test
    void anEmptyResultIsValid() {
        assertThat(AuditPayloadFilter.filter(ALLOW, Map.of("colour", "blue"))).isEmpty();
        assertThat(AuditPayloadFilter.filter(AuditPayloadAllowlist.none(), Map.of("resourceName", "x"))).isEmpty();
        assertThat(AuditPayloadFilter.filter(ALLOW, Map.of())).isEmpty();
    }

    @Test
    void credentialLikeFieldsNeverSurvive() {
        Map<String, Object> hostile = new LinkedHashMap<>();
        for (String key : List.of("password", "ownerPassword", "passcode", "secret", "clientSecret", "otp", "otpCode",
                "token", "accessToken", "refresh_token", "apiKey", "api-key", "privateKey", "authorization", "cookie",
                "Set-Cookie", "pin", "hash", "salt", "cvv", "cardNumber")) {
            hostile.put(key, "leak");
        }
        hostile.put("resourceName", "Room 1");

        assertThat(AuditPayloadFilter.filter(ALLOW, hostile)).containsOnlyKeys("resourceName");
    }

    @Test
    void aNestedSecretUnderAnAllowedStructureCannotLeak() {
        Map<String, Object> window = new HashMap<>();
        window.put("startAt", Instant.parse("2026-10-01T18:00:00Z"));
        window.put("token", "leak");
        window.put("deeper", Map.of("password", "leak"));
        Map<String, Object> item = new HashMap<>();
        item.put("id", "A");
        item.put("secret", "leak");

        Map<String, Object> filtered = AuditPayloadFilter.filter(ALLOW,
                Map.of("window", window, "items", List.of(item, Map.of("apiKey", "leak"))));

        assertThat(filtered).isEqualTo(Map.of(
                "window", Map.of("startAt", "2026-10-01T18:00:00Z"),
                "items", List.of(Map.of("id", "A"))));
    }

    @Test
    void anAllowedLeafThatIsActuallyAnObjectOrListIsDropped() {
        assertThat(AuditPayloadFilter.filter(ALLOW, Map.of(
                "resourceName", Map.of("password", "leak"),
                "resourceId", List.of("x"))))
                .isEmpty();
    }

    @Test
    void listsKeepOnlyScalarsAndNonScalarObjectsAreNeverSerialized() {
        UUID booking = UUID.randomUUID();
        Object pojo = new Object() {
            @SuppressWarnings("unused")
            public String getPassword() {
                return "leak";
            }
        };

        Map<String, Object> filtered = AuditPayloadFilter.filter(ALLOW, Map.of(
                "bookingIds", List.of(booking, Map.of("token", "leak"), pojo),
                "amount", new BigDecimal("150.00"),
                "items", List.of(pojo)));

        assertThat(filtered).isEqualTo(Map.of("bookingIds", List.of(booking.toString()), "amount", new BigDecimal("150.00")));
    }

    @Test
    void floatingPointIsNotAPayloadValue() {
        assertThat(AuditPayloadFilter.filter(ALLOW, Map.of("amount", 1.5d))).isEmpty();
    }

    // ---- path syntax belongs to the allowlist only --------------------------------------------------------------

    @Test
    void aFlatDottedKeyCannotImpersonateANestedField() {
        assertThat(AuditPayloadFilter.filter(ALLOW, Map.of("window.startAt", "2026-10-01T18:00:00Z"))).isEmpty();
    }

    @Test
    void aFlatListPathKeyCannotImpersonateAListElementField() {
        assertThat(AuditPayloadFilter.filter(ALLOW, Map.of("items[].id", "A"))).isEmpty();
        assertThat(AuditPayloadFilter.filter(ALLOW, Map.of("bookingIds[]", List.of("x")))).isEmpty();
    }

    @Test
    void theProperNestedFormsAreStillKept() {
        assertThat(AuditPayloadFilter.filter(ALLOW, Map.of(
                "window", Map.of("startAt", "2026-10-01T18:00:00Z"),
                "items", List.of(Map.of("id", "A")))))
                .isEqualTo(Map.of(
                        "window", Map.of("startAt", "2026-10-01T18:00:00Z"),
                        "items", List.of(Map.of("id", "A"))));
    }

    @Test
    void pathLikeKeysLetNothingElseThroughAtAnyDepth() {
        Map<String, Object> hostile = new LinkedHashMap<>();
        hostile.put("window.startAt", Map.of("token", "leak", "note", "unknown"));
        hostile.put("items[].id", List.of("leak"));
        hostile.put("window[]", "leak");
        hostile.put("resourceName ", "leak");
        hostile.put("resource-name", "leak");
        hostile.put("_resourceName", "leak");
        hostile.put("", "leak");
        hostile.put("window", Map.of("startAt", "2026-10-01T18:00:00Z", "startAt.x", "leak", "a.b", "leak"));
        hostile.put("items", List.of(Map.of("id", "A", "id.secret", "leak", "[]", "leak")));

        Map<String, Object> filtered = AuditPayloadFilter.filter(ALLOW, hostile);

        assertThat(filtered).isEqualTo(Map.of(
                "window", Map.of("startAt", "2026-10-01T18:00:00Z"),
                "items", List.of(Map.of("id", "A"))));
    }

    @Test
    void theCandidateIsNeverReturnedOrMutated() {
        Map<String, Object> candidate = new HashMap<>(Map.of("resourceName", "Room 1", "token", "leak"));

        Map<String, Object> filtered = AuditPayloadFilter.filter(ALLOW, candidate);

        assertThat(filtered).isNotSameAs(candidate);
        assertThat(candidate).containsOnlyKeys("resourceName", "token");
    }
}
