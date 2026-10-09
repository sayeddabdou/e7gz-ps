package ps.e7gz.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.support.IntegrationTest;

/** E7-42: how {@code Idempotency.request} identifies a write (principal, endpoint, key, fingerprint). */
class IdempotencyRequestTest {

    private static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final IdempotencyService service = new IdempotencyService(JSON, null,
            RequestFingerprintTest.fingerprint(IntegrationTest.TEST_IDEMPOTENCY_FINGERPRINT_KEY));

    private static final AuthenticatedSubject CUSTOMER = new AuthenticatedSubject(AuthSubjectType.CUSTOMER, UUID.randomUUID());
    private static final UUID KEY = UUID.fromString("018f3c2e-7b1a-7c4d-9e2f-0a1b2c3d4e5f");
    private static final String CANCEL = "POST /api/v1/customer/bookings/{bookingId}/cancel";

    record Body(Instant startAt, String reasonText) {
    }

    @Test
    void theRecordIdentityIsThePrincipalTheEndpointTemplateAndTheKey() {
        UUID booking = UUID.randomUUID();

        IdempotentRequest request = service.request(CUSTOMER, CANCEL, KEY, Map.of("bookingId", booking), null);

        assertThat(request.principalType()).isEqualTo(ActorType.CUSTOMER);
        assertThat(request.principalId()).isEqualTo(CUSTOMER.id());
        assertThat(request.endpoint()).isEqualTo(CANCEL);
        assertThat(request.key()).isEqualTo("018f3c2e-7b1a-7c4d-9e2f-0a1b2c3d4e5f");
        assertThat(request.fingerprint()).matches("^[0-9a-f]{64}$");
    }

    @Test
    void thePathVariablesArePartOfTheFingerprint() {
        String first = service.request(CUSTOMER, CANCEL, KEY, Map.of("bookingId", UUID.randomUUID()), null).fingerprint();
        String second = service.request(CUSTOMER, CANCEL, KEY, Map.of("bookingId", UUID.randomUUID()), null).fingerprint();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void theBoundValueIsFingerprintedNotItsSpelling() {
        String endpoint = "POST /api/v1/customer/bookings";
        Body utc = new Body(OffsetDateTime.parse("2026-10-12T17:00:00Z").toInstant(), "x");
        Body cairo = new Body(OffsetDateTime.parse("2026-10-12T20:00:00+03:00").toInstant(), "x");
        Body later = new Body(OffsetDateTime.parse("2026-10-12T17:00:01Z").toInstant(), "x");

        assertThat(service.request(CUSTOMER, endpoint, KEY, Map.of(), utc).fingerprint())
                .isEqualTo(service.request(CUSTOMER, endpoint, KEY, Map.of(), cairo).fingerprint())
                .isNotEqualTo(service.request(CUSTOMER, endpoint, KEY, Map.of(), later).fingerprint());
    }

    record ProtectedBody(String reasonText, String ownerPassword) {
    }

    @Test
    void independentInstancesWithTheSameKeyAgreeAndAnotherKeyDiffersOnlyWhereASecretIs() {
        String endpoint = "POST /api/v1/customer/bookings";
        ProtectedBody withSecret = new ProtectedBody("x", "4821");
        ProtectedBody withoutSecret = new ProtectedBody("x", null);
        // As two replicas, or one before and after a restart: separate mappers, separate keys objects, same key value.
        IdempotencyService replicaA = service(IntegrationTest.TEST_IDEMPOTENCY_FINGERPRINT_KEY);
        IdempotencyService replicaB = service(IntegrationTest.TEST_IDEMPOTENCY_FINGERPRINT_KEY);
        IdempotencyService otherKey = service(RequestFingerprintTest.OTHER_KEY);

        assertThat(replicaA.request(CUSTOMER, endpoint, KEY, Map.of(), withSecret).fingerprint())
                .isEqualTo(replicaB.request(CUSTOMER, endpoint, KEY, Map.of(), withSecret).fingerprint())
                .isNotEqualTo(otherKey.request(CUSTOMER, endpoint, KEY, Map.of(), withSecret).fingerprint())
                .isNotEqualTo(replicaA.request(CUSTOMER, endpoint, KEY, Map.of(), new ProtectedBody("x", "4822"))
                        .fingerprint());
        assertThat(replicaA.request(CUSTOMER, endpoint, KEY, Map.of(), withoutSecret).fingerprint())
                .isEqualTo(otherKey.request(CUSTOMER, endpoint, KEY, Map.of(), withoutSecret).fingerprint());
    }

    @Test
    void aNullSecretAndAnEmptySecretAreDifferentRequests() {
        // Absent and null bind to the same null in a typed body (IdempotencyHttpTest proves it over HTTP); "" is a value.
        String endpoint = "POST /api/v1/customer/bookings";
        String nullSecret = service.request(CUSTOMER, endpoint, KEY, Map.of(), new ProtectedBody("x", null)).fingerprint();

        assertThat(nullSecret).isNotEqualTo(
                service.request(CUSTOMER, endpoint, KEY, Map.of(), new ProtectedBody("x", "")).fingerprint());
    }

    private static IdempotencyService service(String key) {
        return new IdempotencyService(JsonMapper.builder().addModule(new JavaTimeModule()).build(), null,
                RequestFingerprintTest.fingerprint(key));
    }

    @Test
    void aNullBodyAndAnEmptyBodyAreDifferentRequests() {
        String endpoint = "POST /api/v1/customer/bookings";

        assertThat(service.request(CUSTOMER, endpoint, KEY, Map.of(), null).fingerprint())
                .isNotEqualTo(service.request(CUSTOMER, endpoint, KEY, Map.of(), Map.of()).fingerprint());
    }

    @Test
    void anEndpointOutsideThePrincipalsRouteGroupIsADefect() {
        AuthenticatedSubject venue = new AuthenticatedSubject(AuthSubjectType.VENUE, UUID.randomUUID());

        assertThatThrownBy(() -> service.request(venue, "POST /api/v1/customer/bookings", KEY, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("/api/v1/venue/");
        assertThatThrownBy(() -> service.request(CUSTOMER, "POST /customer/bookings", KEY, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.request(CUSTOMER, "GET /api/v1/customer/bookings", KEY, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thePathVariablesMustBeExactlyTheTemplates() {
        assertThatThrownBy(() -> service.request(CUSTOMER, CANCEL, KEY, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bookingId");
        assertThatThrownBy(() -> service.request(CUSTOMER, CANCEL, KEY,
                Map.of("bookingId", UUID.randomUUID(), "extra", 1), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.request(CUSTOMER, "POST /api/v1/customer/bookings", KEY,
                Map.of("bookingId", UUID.randomUUID()), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRequestKeepsTheLimitsOfTheTable() {
        String fingerprint = "a".repeat(64);
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> new IdempotentRequest(ActorType.CUSTOMER, id, "POST /" + "x".repeat(200), "k",
                fingerprint)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotentRequest(ActorType.CUSTOMER, id, "POST /x", "", fingerprint))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotentRequest(ActorType.CUSTOMER, id, "POST /x", "k", "A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
