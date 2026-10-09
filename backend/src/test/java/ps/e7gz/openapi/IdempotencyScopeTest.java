package ps.e7gz.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * E7-42 — ADR-015's scope over the whole committed contract: every write is classified, the ones in scope require the
 * {@code Idempotency-Key} and declare {@code 422 IDEMPOTENCY_KEY_REUSED}, every other one keeps it optional (CLAUDE.md
 * §5; PO decision 3 Oct 2026). A new write fails this test until it is put on one side: an ADR-015 operation (Late
 * Confirm, attendance, tournaments, admin remediation, points/wallet/referral) goes into {@link #MANDATORY}.
 */
class IdempotencyScopeTest {

    /** ADR-015 / arch 08 §6 scope as it exists in the contract today, with the task that implements each. */
    static final Set<String> MANDATORY = Set.of(
            "post /api/v1/customer/bookings",                                   // booking create, E7-46
            "post /api/v1/customer/bookings/{bookingId}/cancel",                // customer cancel, E7-49
            "post /api/v1/venue/bookings/{bookingId}/accept",                   // venue accept, E7-47
            "post /api/v1/venue/bookings/{bookingId}/reject",                   // venue reject, E7-47
            "post /api/v1/venue/bookings/{bookingId}/reject-after-expiry",      // reject after expiry, E7-47
            "post /api/v1/venue/bookings",                                      // manual booking create, E7-48
            "post /api/v1/venue/bookings/{bookingId}/cancel-manual");           // manual booking cancel, E7-48

    /** Writes outside ADR-015's scope, so the header is accepted but optional. */
    static final Map<String, List<String>> OPTIONAL = Map.of(
            "authentication and phone verification (no commitment; arch 09 §1.2)", List.of(
                    "post /api/v1/customer/auth/register", "post /api/v1/customer/auth/otp/send",
                    "post /api/v1/customer/auth/verify-phone", "post /api/v1/customer/auth/login",
                    "post /api/v1/customer/auth/refresh", "post /api/v1/customer/auth/logout",
                    "post /api/v1/venue/auth/login", "post /api/v1/venue/auth/refresh",
                    "post /api/v1/venue/auth/logout", "post /api/v1/admin/auth/login",
                    "post /api/v1/admin/auth/refresh", "post /api/v1/admin/auth/logout"),
            "admin area, venue and venue-account setup (PO decision 3 Oct 2026: not remediation)", List.of(
                    "post /api/v1/admin/areas", "patch /api/v1/admin/areas/{areaId}",
                    "post /api/v1/admin/areas/{areaId}/activate", "post /api/v1/admin/areas/{areaId}/deactivate",
                    "post /api/v1/admin/venues", "patch /api/v1/admin/venues/{venueId}",
                    "post /api/v1/admin/venues/{venueId}/onboarding/complete",
                    "post /api/v1/admin/venues/{venueId}/activate", "post /api/v1/admin/venues/{venueId}/deactivate",
                    "post /api/v1/admin/venues/{venueId}/suspend", "post /api/v1/admin/venues/{venueId}/venue-account",
                    "post /api/v1/admin/venues/{venueId}/resources", "post /api/v1/admin/venues/{venueId}/images",
                    "put /api/v1/admin/venues/{venueId}/images/order",
                    "post /api/v1/admin/venues/{venueId}/images/{imageId}/confirm",
                    "post /api/v1/admin/venues/{venueId}/images/{imageId}/remove",
                    "post /api/v1/admin/venue-accounts/{venueAccountId}/disable",
                    "post /api/v1/admin/venue-accounts/{venueAccountId}/enable",
                    "post /api/v1/admin/venue-accounts/{venueAccountId}/reset-login-password",
                    "post /api/v1/admin/venue-accounts/{venueAccountId}/reset-owner-password"),
            "venue setup: resources, prices, photos, hours, settings, Owner Password (Sprint 1 contract)", List.of(
                    "post /api/v1/venue/resources", "post /api/v1/venue/resources/{resourceId}/disable",
                    "post /api/v1/venue/resources/{resourceId}/re-enable",
                    "post /api/v1/venue/resources/{resourceId}/archive",
                    "post /api/v1/venue/resources/{resourceId}/restore",
                    "post /api/v1/venue/resources/{resourceId}/console", "put /api/v1/venue/pricing",
                    "put /api/v1/venue/resources/{resourceId}/price-override",
                    "delete /api/v1/venue/resources/{resourceId}/price-override", "post /api/v1/venue/images",
                    "put /api/v1/venue/images/order", "post /api/v1/venue/images/{imageId}/confirm",
                    "post /api/v1/venue/images/{imageId}/remove", "post /api/v1/venue/opening-hours/preview",
                    "post /api/v1/venue/opening-hours/commit", "post /api/v1/venue/owner-password",
                    "patch /api/v1/venue/settings"),
            "notifications and push (SPRINT-2-CONTRACT.md: optional)", List.of(
                    "post /api/v1/customer/notifications/{notificationId}/read",
                    "post /api/v1/customer/notifications/devices",
                    "post /api/v1/customer/notifications/devices/{deviceId}/revoke",
                    "post /api/v1/venue/push-subscriptions",
                    "post /api/v1/venue/push-subscriptions/{subscriptionId}/revoke"));

    private JsonNode contract;

    @BeforeEach
    void readContract() throws Exception {
        contract = OpenApiDocument.parse(Files.readString(OpenApiDocument.COMMITTED));
    }

    @Test
    void everyWriteInTheContractIsClassified() {
        List<String> optional = OPTIONAL.values().stream().flatMap(List::stream).toList();
        assertThat(optional).doesNotHaveDuplicates().doesNotContainAnyElementsOf(MANDATORY);
        assertThat(writes().keySet()).as("each write must be put in MANDATORY or OPTIONAL (ADR-015)")
                .containsExactlyInAnyOrderElementsOf(Stream.concat(MANDATORY.stream(), optional.stream()).toList());
    }

    @Test
    void writesInScopeRequireTheKeyAndDeclareItsReuseError() {
        MANDATORY.forEach(key -> {
            JsonNode operation = writes().get(key);
            JsonNode header = header(operation);
            assertThat(header.path("required").asBoolean(false)).as(key).isTrue();
            assertThat(header.path("schema").path("format").asText()).as(key).isEqualTo("uuid");
            assertThat(operation.path("responses").path("422").path("description").asText()).as(key)
                    .contains("IDEMPOTENCY_KEY_REUSED");
        });
    }

    @Test
    void writesOutsideTheScopeKeepTheKeyOptional() {
        OPTIONAL.values().stream().flatMap(List::stream).forEach(key ->
                assertThat(header(writes().get(key)).path("required").asBoolean(false)).as(key).isFalse());
    }

    /** "method path" → operation, for every POST / PUT / PATCH / DELETE. */
    private Map<String, JsonNode> writes() {
        Map<String, JsonNode> writes = new TreeMap<>();
        contract.path("paths").properties().forEach(path -> {
            for (String method : List.of("post", "put", "patch", "delete")) {
                if (path.getValue().has(method)) {
                    writes.put(method + " " + path.getKey(), path.getValue().get(method));
                }
            }
        });
        return writes;
    }

    private static JsonNode header(JsonNode operation) {
        List<JsonNode> found = new ArrayList<>();
        operation.path("parameters").forEach(p -> {
            if ("header".equals(p.path("in").asText()) && "idempotency-key".equalsIgnoreCase(p.path("name").asText())) {
                found.add(p);
            }
        });
        assertThat(found).as("exactly one Idempotency-Key header").hasSize(1);
        return found.get(0);
    }
}
