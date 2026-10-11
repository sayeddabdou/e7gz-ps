package ps.e7gz.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * E7-54 — the Sprint 3 contract (arch 09 §3.1, §3.3, §4.1; PO decisions 6 and 11 Oct 2026,
 * packages/api-client/SPRINT-3-CONTRACT.md). Read from the committed contract, which {@link OpenApiContractTest} keeps
 * equal to the controllers; ADR-015's scope over every write is {@link IdempotencyScopeTest}'s.
 */
class Sprint3ContractTest {

    /** Every Sprint 3 operation, with the task that implements it in the comment. */
    static final Set<String> SPRINT_3_OPERATIONS = Set.of(
            "post /api/v1/customer/bookings/{bookingId}/late-confirm/accept",   // E7-58
            "post /api/v1/customer/bookings/{bookingId}/late-confirm/decline",  // E7-58
            "post /api/v1/venue/bookings/{bookingId}/late-confirm",             // E7-58
            "post /api/v1/venue/bookings/{bookingId}/check-in",                 // E7-59
            "post /api/v1/venue/bookings/{bookingId}/late-present",             // E7-59
            "post /api/v1/venue/bookings/{bookingId}/no-show",                  // E7-59
            "post /api/v1/venue/bookings/{bookingId}/cancel-confirmed",         // E7-114
            "get /api/v1/venue/overview",                                       // E7-115
            "post /api/v1/customer/auth/password-reset/request",                // E7-57
            "post /api/v1/customer/auth/password-reset/complete",               // E7-57
            "get /api/v1/customer/profile",                                     // E7-57
            "patch /api/v1/customer/profile",                                   // E7-57
            "post /api/v1/customer/profile/username",                           // E7-57
            "post /api/v1/customer/profile/phone",                              // E7-57
            "post /api/v1/customer/profile/phone/verify",                       // E7-57
            "post /api/v1/customer/profile/email",                              // E7-57, once email verification is decided
            "post /api/v1/customer/profile/email/verify");                      // E7-57, once email verification is decided

    /** ADR-015's scope among them: the Late Confirm, attendance and cancel writes (arch 09 §3.3, §4.1). */
    static final Set<String> IDEMPOTENT_WRITES = Set.of(
            "post /api/v1/customer/bookings/{bookingId}/late-confirm/accept",
            "post /api/v1/customer/bookings/{bookingId}/late-confirm/decline",
            "post /api/v1/venue/bookings/{bookingId}/late-confirm",
            "post /api/v1/venue/bookings/{bookingId}/check-in",
            "post /api/v1/venue/bookings/{bookingId}/late-present",
            "post /api/v1/venue/bookings/{bookingId}/no-show",
            "post /api/v1/venue/bookings/{bookingId}/cancel-confirmed");

    /** Codes that would tell the venue why a customer is ineligible (FR-LCF-05, NFR-19, arch 10 §5.2). */
    static final Set<String> CUSTOMER_REVEALING_CODES = Set.of("COMMITMENT_LIMIT_REACHED", "OVERLAPPING_COMMITMENT",
            "ACCOUNT_BANNED_FOR_THIS_ACTION", "ACCOUNT_DELETED", "PHONE_VERIFICATION_REQUIRED", "CUSTOMER_NOT_ELIGIBLE",
            "LATE_CONFIRM_CUSTOMER_CONFLICT");

    private JsonNode contract;

    @BeforeEach
    void readContract() throws Exception {
        contract = OpenApiDocument.parse(Files.readString(OpenApiDocument.COMMITTED));
    }

    @Test
    void everySprint3OperationIsInTheContract() {
        SPRINT_3_OPERATIONS.forEach(key -> assertThat(operation(key).isMissingNode()).as(key).isFalse());
    }

    @Test
    void lateConfirmAttendanceAndCancelWritesRequireTheIdempotencyKeyAndProfileWritesDoNot() {
        SPRINT_3_OPERATIONS.stream().filter(key -> !key.startsWith("get ")).forEach(key -> {
            JsonNode header = header(operation(key), "Idempotency-Key");
            assertThat(header.path("required").asBoolean(false)).as(key).isEqualTo(IDEMPOTENT_WRITES.contains(key));
        });
    }

    @Test
    void onlyCancelConfirmedNeedsTheOwnerPasswordInItsBody() {
        // arch 09 §4.1: Late Confirm, check-in, late present and no-show are "no"; cancel-confirmed is "yes" (FR-VEN-03).
        String cancel = "post /api/v1/venue/bookings/{bookingId}/cancel-confirmed";
        SPRINT_3_OPERATIONS.stream().filter(key -> !key.equals(cancel)).forEach(key -> {
            assertThat(operation(key).path("responses").has("423")).as(key).isFalse();
            assertThat(properties(requestBody(operation(key)))).as(key).doesNotContain("ownerPassword");
        });
        JsonNode body = requestBody(operation(cancel));
        assertThat(properties(body)).containsExactly("ownerPassword");
        assertThat(body.path("properties").path("ownerPassword").path("writeOnly").asBoolean()).isTrue();
        assertThat(codes(operation(cancel), "423")).containsExactly("OWNER_PASSWORD_REQUIRED", "OWNER_PASSWORD_INVALID");
        assertThat(codes(operation(cancel), "409")).containsExactly("BOOKING_STATE_CHANGED");
    }

    @Test
    void theVenueLateConfirmNeverSaysWhyTheCustomerIsIneligible() {
        // FR-LCF-05, NFR-19: CUSTOMER_NOT_ELIGIBLE_FOR_LATE_CONFIRM is the only customer ineligibility code.
        JsonNode send = operation("post /api/v1/venue/bookings/{bookingId}/late-confirm");
        assertThat(codes(send, "422")).contains("CUSTOMER_NOT_ELIGIBLE_FOR_LATE_CONFIRM");
        send.path("responses").fieldNames().forEachRemaining(status ->
                assertThat(codes(send, status)).as(status).doesNotContainAnyElementsOf(CUSTOMER_REVEALING_CODES));
        // The success body has no reason or detail either.
        assertThat(properties(schema("VenueLateConfirmation")))
                .containsExactlyInAnyOrder("bookingId", "status", "lateConfirmExpiresAt");
        // The customer's own accept is where the schedule conflict is checked (FR-LCF-15).
        assertThat(codes(operation("post /api/v1/customer/bookings/{bookingId}/late-confirm/accept"), "409"))
                .contains("LATE_CONFIRM_CUSTOMER_CONFLICT", "LATE_CONFIRM_RESOURCE_CONFLICT");
    }

    @Test
    void lateConfirmAndAttendanceTimesAreInstantsNeverCountdowns() {
        // NFR-10, arch 09 §1.2: clients never receive remaining seconds.
        Map.of("CustomerBookingLateConfirm", List.of("expiresAt"),
                "VenueBooking", List.of("lateConfirmExpiresAt"),
                "VenueLateConfirmation", List.of("lateConfirmExpiresAt"),
                "VenueBookingTimeWindow", List.of("opensAt", "closesAt"),
                "VenueBookingAttendance", List.of("checkedInAt", "latePresentAt", "noShowAt"),
                "VenueAttendance", List.of("checkedInAt", "latePresentAt", "noShowAt"),
                "CustomerProfile", List.of("usernameChangeAvailableAt")).forEach((name, fields) -> fields.forEach(field ->
                assertThat(schema(name).path("properties").path(field).path("format").asText()).as(name + "." + field)
                        .isEqualTo("date-time")));
        SPRINT_3_OPERATIONS.forEach(key -> refs(operation(key)).forEach(name ->
                assertThat(properties(schema(name))).as(name)
                        .noneMatch(p -> p.toLowerCase().matches(".*(second|remaining|countdown).*"))));
    }

    @Test
    void theCustomerBookingCarriesItsLateConfirmation() {
        // arch 09 §3.3: lateConfirm.expiresAt (FR-LCF-09) and paused (FR-OPS-41).
        assertThat(properties(schema("CustomerBookingDetail"))).contains("lateConfirm");
        assertThat(properties(schema("CustomerBookingLateConfirm"))).containsExactlyInAnyOrder("expiresAt", "paused");
        assertThat(properties(schema("CustomerLateConfirmOutcome")))
                .containsExactlyInAnyOrder("bookingId", "status", "terminalReason");
    }

    @Test
    void theVenueBookingGivesTheAttendanceWindowsAndEvidence() {
        // arch 07 §7.1: the Dashboard enables each action from these instants, never its own rule (E7-63).
        assertThat(properties(schema("VenueBooking"))).contains("lateConfirmExpiresAt", "lateConfirmPaused", "attendance");
        assertThat(properties(schema("VenueBookingAttendance"))).containsExactlyInAnyOrder("checkInWindow",
                "lateWindow", "checkedInAt", "latePresentAt", "noShowAt");
        assertThat(fieldNames(schema("VenueBookingTimeWindow").path("required")))
                .containsExactlyInAnyOrder("opensAt", "closesAt");
        Map.of("check-in", "CHECK_IN_WINDOW_CLOSED", "late-present", "LATE_PRESENT_NOT_AVAILABLE",
                "no-show", "NO_SHOW_NOT_AVAILABLE").forEach((action, code) -> {
                    JsonNode operation = operation("post /api/v1/venue/bookings/{bookingId}/" + action);
                    assertThat(codes(operation, "422")).as(action).contains(code);
                    assertThat(codes(operation, "409")).as(action).containsExactly("BOOKING_STATE_CHANGED");
                });
    }

    @Test
    void theOverviewIsFourFiguresWithMoneyAsStrings() {
        // FR-VEN-35; Money as in SPRINT-2-CONTRACT.md (arch 09 §1.2): never a float.
        JsonNode overview = schema("VenueOverview");
        assertThat(fieldNames(overview.path("required"))).containsExactlyInAnyOrder("todayRevenue", "monthRevenue",
                "todayBookings", "pendingRequests");
        assertThat(properties(overview)).hasSize(4);
        assertThat(overview.path("properties").path("todayRevenue").toString()).contains("#/components/schemas/Money");
        assertThat(overview.path("properties").path("monthRevenue").toString()).contains("#/components/schemas/Money");
        assertThat(overview.path("properties").path("todayBookings").path("type").asText()).isEqualTo("integer");
        assertThat(overview.path("properties").path("pendingRequests").path("type").asText()).isEqualTo("integer");
    }

    @Test
    void passwordResetAndProfileFollowTheAccountRules() {
        // FR-ACC-09: no old password; every session ends (PO decision 11 Oct 2026).
        JsonNode complete = operation("post /api/v1/customer/auth/password-reset/complete");
        assertThat(complete.path("responses").has("204")).isTrue();
        assertThat(properties(requestBody(complete))).containsExactlyInAnyOrder("otpChallengeId", "code", "password");
        assertThat(requestBody(complete).path("properties").path("password").path("writeOnly").asBoolean()).isTrue();
        assertThat(codes(operation("post /api/v1/customer/auth/password-reset/request"), "404")).containsExactly("NOT_FOUND");
        // FR-ACC-07: the cooldown and the second step of a phone change.
        assertThat(codes(operation("post /api/v1/customer/profile/username"), "422"))
                .containsExactlyInAnyOrder("USERNAME_TAKEN", "USERNAME_COOLDOWN_ACTIVE");
        assertThat(properties(requestBody(operation("post /api/v1/customer/profile/phone/verify"))))
                .containsExactlyInAnyOrder("otpChallengeId", "code");
        // Full name only: username, phone and email have their own routes.
        assertThat(properties(requestBody(operation("patch /api/v1/customer/profile")))).containsExactly("fullName");
    }

    @Test
    void customerAndVenueRoutesNeverShareASchema() {
        // arch 09 §1.1: separate DTOs per route group, always.
        SPRINT_3_OPERATIONS.forEach(key -> {
            String other = key.contains("/customer/") ? "Venue" : "Customer";
            assertThat(refs(operation(key))).as(key).noneMatch(ref -> ref.startsWith(other));
        });
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

    /** The error codes the operation declares for one status (its narrowed envelope). */
    private static List<String> codes(JsonNode operation, String status) {
        return fieldNames(operation.path("responses").path(status).path("content").path("application/json")
                .path("schema").path("allOf").path(1).path("properties").path("error").path("properties")
                .path("code").path("enum"));
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
