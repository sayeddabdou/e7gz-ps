package ps.e7gz.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * E7-77 — the Sprint 5 contract (arch 09 §4.2, §5; FRS F-18…F-23; PO decisions 19 Nov 2026,
 * packages/api-client/SPRINT-5-CONTRACT.md). Read from the committed contract, which {@link OpenApiContractTest} keeps
 * equal to the controllers; ADR-015's scope over every write is {@link IdempotencyScopeTest}'s.
 */
class Sprint5ContractTest {

    /** Every Sprint 5 operation, with the task that implements it in the comment. */
    static final Set<String> SPRINT_5_OPERATIONS = Set.of(
            "get /api/v1/venue/maintenance",                                    // E7-81 (PO D1)
            "post /api/v1/venue/maintenance",                                   // E7-81
            "patch /api/v1/venue/maintenance/{maintenanceId}",                  // E7-81
            "delete /api/v1/venue/maintenance/{maintenanceId}",                 // E7-81
            "get /api/v1/venue/closures",                                       // E7-81 (PO D1)
            "post /api/v1/venue/closures/preview",                              // E7-81 (PO D2)
            "post /api/v1/venue/closures",                                      // E7-81
            "patch /api/v1/venue/closures/{closureId}",                         // E7-81
            "delete /api/v1/venue/closures/{closureId}",                        // E7-81
            "get /api/v1/venue/emergencies",                                    // E7-82 (PO D1)
            "post /api/v1/venue/emergencies/venue-closure",                     // E7-82
            "post /api/v1/venue/emergencies/resource-outage",                   // E7-82
            "post /api/v1/venue/emergencies/{emergencyId}/extend/preview",      // E7-82 (PO D2)
            "post /api/v1/venue/emergencies/{emergencyId}/extend",              // E7-82
            "post /api/v1/venue/emergencies/{emergencyId}/end",                 // E7-82, E7-84
            "post /api/v1/venue/emergencies/{emergencyId}/convert/preview",     // E7-82 (PO D2)
            "post /api/v1/venue/emergencies/{emergencyId}/convert",             // E7-82
            "get /api/v1/venue/owner-reviews",                                  // E7-84
            "post /api/v1/venue/owner-reviews/{resourceId}/resolve",            // E7-84
            "get /api/v1/admin/venue-accounts",                                 // E7-78 (E7-37 carry-forward)
            "get /api/v1/admin/owner-reviews",                                  // E7-84 (PO D3)
            "get /api/v1/admin/emergencies");                                   // E7-82 (PO D3)

    /** Saves bound to a preview: the token is required and a grown affected set is answered (FR-VEN-26 pattern). */
    static final Set<String> PREVIEW_COMMITS = Set.of(
            "post /api/v1/venue/closures",
            "patch /api/v1/venue/closures/{closureId}",
            "post /api/v1/venue/emergencies/{emergencyId}/extend",
            "post /api/v1/venue/emergencies/{emergencyId}/convert");

    /** No Owner Password anywhere in these (FR-OPS-06, FR-OPS-11, FR-VEN-04). */
    static final Set<String> NO_OWNER_PASSWORD = Set.of(
            "post /api/v1/venue/emergencies/venue-closure",
            "post /api/v1/venue/emergencies/resource-outage",
            "post /api/v1/venue/emergencies/{emergencyId}/extend",
            "post /api/v1/venue/emergencies/{emergencyId}/end",
            "delete /api/v1/venue/maintenance/{maintenanceId}",
            "delete /api/v1/venue/closures/{closureId}");

    /** Actions on an Emergency or Owner Review another staff member may already have resolved (PO D4). */
    static final Set<String> STATE_DEPENDENT_WRITES = Set.of(
            "post /api/v1/venue/emergencies/{emergencyId}/extend/preview",
            "post /api/v1/venue/emergencies/{emergencyId}/extend",
            "post /api/v1/venue/emergencies/{emergencyId}/end",
            "post /api/v1/venue/emergencies/{emergencyId}/convert/preview",
            "post /api/v1/venue/emergencies/{emergencyId}/convert",
            "post /api/v1/venue/owner-reviews/{resourceId}/resolve",
            "post /api/v1/venue/opening-hours/commit");

    /** Closed code enums shared by every route group (ps.e7gz.common.codes) and the envelope. */
    static final Set<String> SHARED = Set.of("ResponseMeta", "EmergencyScope", "EmergencyStatus",
            "EmergencyConversionTarget", "VenueClosureKind", "OwnerReviewResolution", "VenueAccountStatus",
            "VenueAccountLanguage");

    private JsonNode contract;

    @BeforeEach
    void readContract() throws Exception {
        contract = OpenApiDocument.parse(Files.readString(OpenApiDocument.COMMITTED));
    }

    @Test
    void everySprint5OperationIsInTheContract() {
        SPRINT_5_OPERATIONS.forEach(key -> assertThat(operation(key).isMissingNode()).as(key).isFalse());
    }

    @Test
    void eachRouteGroupUsesOnlyItsOwnSchemas() {
        // arch 09 §1.1: separate DTOs per route group, always.
        SPRINT_5_OPERATIONS.forEach(key -> {
            String prefix = key.contains("/venue/") ? "Venue" : "Admin";
            assertThat(refs(operation(key))).as(key)
                    .map(ref -> ref.replaceFirst("^DataResponse", "").replaceFirst("^PageView", "")
                            .replaceFirst("^List", ""))
                    .allMatch(ref -> ref.startsWith(prefix) || SHARED.contains(ref) || ref.isEmpty());
        });
    }

    @Test
    void theOwnerPasswordIsWriteOnlyAndNeverAnswered() {
        // ADR-011, arch 09 §4.3: body only, never returned, never echoed.
        SPRINT_5_OPERATIONS.forEach(key -> {
            JsonNode pin = requestBody(operation(key)).path("properties").path("ownerPassword");
            if (!pin.isMissingNode()) {
                assertThat(pin.path("writeOnly").asBoolean(false)).as(key).isTrue();
                assertThat(pin.path("format").asText()).as(key).isEqualTo("password");
            }
            operation(key).path("responses").properties().forEach(response -> {
                if (response.getKey().startsWith("2")) {
                    assertThat(response.getValue().toString()).as(key).doesNotContain("ownerPassword");
                    List<String> answered = new ArrayList<>();
                    collect(response.getValue(), answered);
                    answered.forEach(name -> assertThat(properties(schema(name))).as(key + " " + name)
                            .doesNotContain("ownerPassword", "password", "passwordHash", "ownerPin"));
                }
            });
        });
    }

    @Test
    void savesBoundToAPreviewRequireItsTokenAndAnswerAGrownSet() {
        // PO decision D2 (19 Nov 2026): preview + commit, like opening hours (FR-VEN-26).
        PREVIEW_COMMITS.forEach(key -> {
            assertThat(fieldNames(requestBody(operation(key)).path("required"))).as(key).contains("previewToken");
            assertThat(codes(operation(key), "409")).as(key).contains("AFFECTED_SET_CHANGED_REFRESH_PREVIEW");
        });
        for (String preview : List.of("post /api/v1/venue/closures/preview",
                "post /api/v1/venue/emergencies/{emergencyId}/extend/preview",
                "post /api/v1/venue/emergencies/{emergencyId}/convert/preview")) {
            List<String> answered = refs(operation(preview));
            String body = answered.stream().filter(name -> name.endsWith("Preview") && !name.startsWith("DataResponse"))
                    .findFirst().orElseThrow();
            assertThat(properties(schema(body))).as(preview)
                    .containsExactlyInAnyOrder("previewToken", "affected", "ownerPasswordRequired");
        }
    }

    @Test
    void emergenciesAndDeletesNeverAskForTheOwnerPassword() {
        NO_OWNER_PASSWORD.forEach(key -> {
            assertThat(properties(requestBody(operation(key)))).as(key).doesNotContain("ownerPassword");
            assertThat(operation(key).path("responses").has("423")).as(key).isFalse();
        });
    }

    @Test
    void resolvingAnOwnerReviewNeedsThePinAndListsWhatBlocksIt() {
        // FR-OPS-33 (Owner Password), FR-OPS-43 + arch 09 §4.2 (blocking commitments in the refusal).
        JsonNode resolve = operation("post /api/v1/venue/owner-reviews/{resourceId}/resolve");
        assertThat(fieldNames(requestBody(resolve).path("required"))).contains("action", "ownerPassword");
        assertThat(codes(resolve, "422")).contains("OWNER_REVIEW_RESOLUTION_BLOCKED");
        assertThat(codes(resolve, "423")).contains("OWNER_PASSWORD_REQUIRED", "OWNER_PASSWORD_INVALID");
    }

    @Test
    void stateDependentWritesDeclareTheOperationalRefusal() {
        // PO decision D4 (19 Nov 2026): one general 409 code.
        STATE_DEPENDENT_WRITES.forEach(key ->
                assertThat(codes(operation(key), "409")).as(key).contains("OPERATIONAL_STATE_CHANGED"));
    }

    @Test
    void anEmergencyBecomesAnHoursChangeThroughTheHoursCommit() {
        // PO decision D5 (19 Nov 2026): optional emergencyId on the hours commit; /convert has no hours proposal.
        JsonNode commit = requestBody(operation("post /api/v1/venue/opening-hours/commit"));
        assertThat(properties(commit)).contains("emergencyId");
        assertThat(fieldNames(commit.path("required"))).doesNotContain("emergencyId");
        assertThat(properties(schema("VenueEmergencyConversion")))
                .containsExactlyInAnyOrder("target", "closure", "maintenance");
    }

    @Test
    void theAdminMonitorsAreReadOnly() {
        // PO decision D3 (19 Nov 2026): no admin action; resolution stays with the venue (FR-OPS-33).
        contract.path("paths").properties().forEach(path -> {
            if (path.getKey().startsWith("/api/v1/admin/owner-reviews")
                    || path.getKey().startsWith("/api/v1/admin/emergencies")) {
                assertThat(path.getValue().fieldNames()).toIterable().as(path.getKey()).containsExactly("get");
            }
        });
    }

    @Test
    void theVenueAccountListShowsNoSecret() {
        // E7-37 carry-forward (PO decision 4 Oct 2026): never a password, hash or Owner PIN.
        assertThat(properties(schema("AdminVenueAccountListItem"))).containsExactlyInAnyOrder("id", "venueId",
                "loginIdentifier", "accountStatus", "venueName", "languagePreference");
        JsonNode list = operation("get /api/v1/admin/venue-accounts");
        assertThat(parameterNames(list)).contains("accountStatus", "cursor", "limit");
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

    private static List<String> parameterNames(JsonNode operation) {
        List<String> names = new ArrayList<>();
        operation.path("parameters").forEach(p -> names.add(p.path("name").asText()));
        return names;
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
