package ps.e7gz.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * E7-39 — the Sprint 2 contract (arch 09 §3.3, §4.1; PO decisions 5 Oct 2026, packages/api-client/SPRINT-2-CONTRACT.md).
 * Read from the committed contract, which {@link OpenApiContractTest} keeps equal to the controllers; the conventions
 * every operation shares are {@link OpenApiProductionContractTest}'s.
 */
class Sprint2ContractTest {

    /** Every Sprint 2 operation, with the task that implements it in the comment. */
    static final Set<String> SPRINT_2_OPERATIONS = Set.of(
            "post /api/v1/customer/bookings",                                   // E7-46
            "get /api/v1/customer/bookings/quote",                              // E7-46
            "get /api/v1/customer/bookings",                                    // E7-49
            "get /api/v1/customer/bookings/{bookingId}",                        // E7-49
            "post /api/v1/customer/bookings/{bookingId}/cancel",                // E7-49
            "get /api/v1/customer/notifications",                               // E7-43
            "post /api/v1/customer/notifications/{notificationId}/read",        // E7-43
            "post /api/v1/customer/notifications/devices",                      // E7-43
            "post /api/v1/customer/notifications/devices/{deviceId}/revoke",    // E7-43
            "get /api/v1/venue/requests/pending",                               // E7-47
            "get /api/v1/venue/requests/missed",                                // E7-47
            "post /api/v1/venue/bookings/{bookingId}/accept",                   // E7-47
            "post /api/v1/venue/bookings/{bookingId}/reject",                   // E7-47
            "post /api/v1/venue/bookings/{bookingId}/reject-after-expiry",      // E7-47
            "post /api/v1/venue/bookings",                                      // E7-48
            "post /api/v1/venue/bookings/{bookingId}/cancel-manual",            // E7-48
            "get /api/v1/venue/bookings",                                       // E7-49
            "get /api/v1/venue/bookings/{bookingId}",                           // E7-49
            "get /api/v1/venue/stream",                                         // E7-44
            "get /api/v1/venue/push-subscriptions/vapid-public-key",            // E7-44
            "post /api/v1/venue/push-subscriptions",                            // E7-44
            "post /api/v1/venue/push-subscriptions/{subscriptionId}/revoke");   // E7-44

    /** ADR-015's scope among them: booking- and commitment-moving writes need the Idempotency-Key (arch 09 §3.3, §4.1). */
    static final Set<String> IDEMPOTENT_WRITES = Set.of(
            "post /api/v1/customer/bookings",
            "post /api/v1/customer/bookings/{bookingId}/cancel",
            "post /api/v1/venue/bookings/{bookingId}/accept",
            "post /api/v1/venue/bookings/{bookingId}/reject",
            "post /api/v1/venue/bookings/{bookingId}/reject-after-expiry",
            "post /api/v1/venue/bookings",
            "post /api/v1/venue/bookings/{bookingId}/cancel-manual");

    private JsonNode contract;

    @BeforeEach
    void readContract() throws Exception {
        contract = OpenApiDocument.parse(Files.readString(OpenApiDocument.COMMITTED));
    }

    @Test
    void everySprint2OperationIsInTheContract() {
        SPRINT_2_OPERATIONS.forEach(key -> assertThat(operation(key).isMissingNode()).as(key).isFalse());
    }

    @Test
    void bookingWritesRequireTheIdempotencyKeyAndOtherWritesDoNot() {
        SPRINT_2_OPERATIONS.stream().filter(key -> key.startsWith("post ")).forEach(key -> {
            JsonNode header = header(operation(key), "Idempotency-Key");
            assertThat(header.path("required").asBoolean(false)).as(key).isEqualTo(IDEMPOTENT_WRITES.contains(key));
        });
    }

    @Test
    void noSprint2ActionNeedsTheOwnerPassword() {
        // FR-BOK-14/17/21, FR-MAN-02/08: accept, reject, manual booking and its cancel are "no" in arch 09 §4.1.
        SPRINT_2_OPERATIONS.forEach(key -> {
            assertThat(operation(key).path("responses").has("423")).as(key).isFalse();
            assertThat(properties(requestBody(operation(key)))).as(key).doesNotContain("ownerPassword");
        });
    }

    @Test
    void venueRejectNeedsANonBlankReason() {
        // FR-BOK-17: the venue-supplied reason is mandatory and separate from the reason code.
        JsonNode body = requestBody(operation("post /api/v1/venue/bookings/{bookingId}/reject"));
        assertThat(fieldNames(body.path("required"))).containsExactly("reasonText");
        assertThat(body.path("properties").path("reasonText").path("minLength").asInt()).isEqualTo(1);
        assertThat(body.path("properties").path("reasonText").path("pattern").asText()).isNotEmpty();
        assertThat(body.path("properties").path("reasonText").path("maxLength").asInt()).isEqualTo(200);
    }

    @Test
    void anActionOnABookingThatChangedMeanwhileIs409BookingStateChanged() {
        // PO decision 5 Oct 2026 (E7-39), arch 09 §1.5.
        List.of("post /api/v1/customer/bookings/{bookingId}/cancel",
                "post /api/v1/venue/bookings/{bookingId}/accept",
                "post /api/v1/venue/bookings/{bookingId}/reject",
                "post /api/v1/venue/bookings/{bookingId}/reject-after-expiry",
                "post /api/v1/venue/bookings/{bookingId}/cancel-manual").forEach(key ->
                assertThat(operation(key).path("responses").path("409").path("description").asText()).as(key)
                        .isEqualTo("error.code: BOOKING_STATE_CHANGED"));
    }

    @Test
    void theCustomerBookingDetailFollowsArch09AndLeavesOutAHiddenVenuePhone() {
        JsonNode detail = schema("CustomerBookingDetail");
        assertThat(properties(detail)).contains("bookingId", "status", "displayBucket", "terminalReason", "venue",
                "resource", "startAt", "endAt", "nominalBookedMinutes", "venuePriceTotal", "bookingFee", "totalAmount",
                "venueResponseExpiresAt", "attendanceResult", "history");
        // NFR-18, FR-PTS-20: absent from the payload when hidden — so optional, and never null.
        JsonNode venue = schema("CustomerBookingVenue");
        assertThat(fieldNames(venue.path("required"))).containsExactlyInAnyOrder("id", "name");
        assertThat(venue.path("properties").path("phone").path("type").toString()).doesNotContain("null");
        // FR-PTS-13: the fee carries the "free" flag.
        assertThat(properties(schema("CustomerBookingFee"))).containsExactlyInAnyOrder("amount", "currency", "displayAsFree");
    }

    @Test
    void theVenueSeesTheCustomerPhoneOnlyWhenAllowed() {
        // arch 10 §5.1: absent when the retention rule hides it; manual bookings carry what the venue typed.
        JsonNode customer = schema("VenueBookingCustomer");
        assertThat(fieldNames(customer.path("required"))).containsExactly("username");
        assertThat(customer.path("properties").path("phone").path("type").toString()).doesNotContain("null");
        assertThat(properties(schema("VenueBooking"))).contains("customer", "walkIn", "source", "businessDate",
                "venueResponseExpiresAt", "rejectReasonText");
    }

    @Test
    void customerAndVenueRoutesNeverShareABookingSchema() {
        // arch 09 §1.1: separate DTOs per route group, always.
        SPRINT_2_OPERATIONS.forEach(key -> {
            String surface = key.contains("/customer/") ? "Customer" : "Venue";
            String other = surface.equals("Customer") ? "Venue" : "Customer";
            List<String> refs = refs(operation(key));
            assertThat(refs).as(key).noneMatch(ref -> ref.startsWith(other));
        });
    }

    @Test
    void theVenueStreamIsAnEventStreamOfChangeSignals() {
        // ADR-014, arch 08 §5: signals only, never business payloads.
        JsonNode ok = operation("get /api/v1/venue/stream").path("responses").path("200");
        assertThat(ok.path("content").has("text/event-stream")).isTrue();
        assertThat(properties(schema("VenueStreamSignal"))).containsExactlyInAnyOrder("type", "id", "reason");
    }

    @Test
    void pushRegistrationTakesWhatTheTablesStore() {
        // notification.device_token and notification.venue_push_subscription (arch 04).
        assertThat(fieldNames(requestBody(operation("post /api/v1/customer/notifications/devices")).path("required")))
                .containsExactlyInAnyOrder("token", "platform");
        JsonNode subscription = requestBody(operation("post /api/v1/venue/push-subscriptions"));
        assertThat(fieldNames(subscription.path("required"))).containsExactlyInAnyOrder("endpoint", "keys");
        assertThat(subscription.path("properties").path("endpoint").path("pattern").asText()).startsWith("^https://");
        assertThat(fieldNames(schema("VenuePushSubscriptionKeys").path("required")))
                .containsExactlyInAnyOrder("p256dh", "auth");
        // Revoked, never deleted (arch 08 §4).
        contract.path("paths").properties().forEach(path -> {
            if (path.getKey().contains("/devices") || path.getKey().contains("/push-subscriptions")) {
                assertThat(path.getValue().has("delete")).as(path.getKey()).isFalse();
            }
        });
        assertThat(operation("post /api/v1/customer/notifications/devices/{deviceId}/revoke").path("responses")
                .has("204")).isTrue();
        assertThat(operation("post /api/v1/venue/push-subscriptions/{subscriptionId}/revoke").path("responses")
                .has("204")).isTrue();
    }

    private JsonNode operation(String key) {
        String[] parts = key.split(" ", 2);
        return contract.path("paths").path(parts[1]).path(parts[0]);
    }

    private JsonNode schema(String name) {
        return contract.path("components").path("schemas").path(name);
    }

    private JsonNode requestBody(JsonNode operation) {
        JsonNode body = operation.path("requestBody").path("content").path("application/json").path("schema");
        String ref = body.path("$ref").asText();
        return ref.isEmpty() ? body : schema(ref.substring(ref.lastIndexOf('/') + 1));
    }

    private static JsonNode header(JsonNode operation, String name) {
        return StreamSupport.stream(operation.path("parameters").spliterator(), false)
                .filter(p -> "header".equals(p.path("in").asText()) && name.equals(p.path("name").asText()))
                .findFirst().orElseThrow();
    }

    /** Every schema the operation's request and success responses reference, followed transitively. */
    private List<String> refs(JsonNode operation) {
        List<String> found = new ArrayList<>();
        collect(operation.path("requestBody"), found);
        operation.path("responses").properties().forEach(response -> {
            if (response.getKey().startsWith("2")) {
                collect(response.getValue(), found);
            }
        });
        return found;
    }

    private void collect(JsonNode node, List<String> found) {
        if (node.isObject()) {
            String ref = node.path("$ref").asText();
            if (!ref.isEmpty()) {
                String name = ref.substring(ref.lastIndexOf('/') + 1);
                if (!found.contains(name)) {
                    found.add(name);
                    collect(schema(name), found);
                }
            }
            node.forEach(child -> collect(child, found));
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, found));
        }
    }

    private static List<String> properties(JsonNode schema) {
        List<String> names = new ArrayList<>();
        schema.path("properties").fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> fieldNames(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
