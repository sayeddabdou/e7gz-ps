package ps.e7gz.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * E7-88 — the Sprint 6 contract (arch 09 §3.4, §4.1, §5; FRS §4.10; PO decision 26 Nov 2026,
 * packages/api-client/SPRINT-6-CONTRACT.md). Read from the committed contract, which {@link OpenApiContractTest} keeps
 * equal to the controllers. The writes built by E7-91/E7-92/E7-93 are checked by their own tests; this class checks the
 * read routes the contract adds and that every Sprint 6 operation keeps to its own route group's schemas.
 */
class Sprint6ContractTest {

    /** The read routes of the Sprint 6 contract. */
    static final Set<String> SPRINT_6_READS = Set.of(
            "get /api/v1/customer/tournaments",                 // arch 09 §3.4 (public Discover)
            "get /api/v1/customer/tournaments/mine",            // PO 26 Nov 2026
            "get /api/v1/customer/tournaments/{tournamentId}",  // PO 26 Nov 2026
            "get /api/v1/venue/tournaments",                    // arch 09 §4.1
            "get /api/v1/venue/tournaments/{tournamentId}",     // PO 26 Nov 2026
            "get /api/v1/admin/tournaments",                    // arch 09 §5
            "get /api/v1/admin/tournaments/{tournamentId}");    // PO 26 Nov 2026

    /** Closed code enums shared by every route group (ps.e7gz.common.codes), money and the envelope. */
    static final Set<String> SHARED = Set.of("ResponseMeta", "Money", "Currency", "Activity", "PlayMode", "ConsoleType",
            "TournamentHostType", "TournamentStatus", "TournamentVisibility", "TournamentTerminalReason",
            "TournamentParticipantStatus");

    /** The schemas each read answers: the item of a list, or the detail. */
    static final Map<String, String> ANSWERS = Map.of(
            "get /api/v1/customer/tournaments", "CustomerTournamentListing",
            "get /api/v1/customer/tournaments/mine", "CustomerTournamentListing",
            "get /api/v1/customer/tournaments/{tournamentId}", "CustomerTournamentDetail",
            "get /api/v1/venue/tournaments", "VenueTournamentListing",
            "get /api/v1/venue/tournaments/{tournamentId}", "VenueTournamentDetail",
            "get /api/v1/admin/tournaments", "AdminTournamentListing",
            "get /api/v1/admin/tournaments/{tournamentId}", "AdminTournamentDetail");

    private JsonNode contract;

    @BeforeEach
    void readContract() throws Exception {
        contract = OpenApiDocument.parse(Files.readString(OpenApiDocument.COMMITTED));
    }

    @Test
    void everySprint6ReadIsInTheContract() {
        SPRINT_6_READS.forEach(key -> assertThat(operation(key).isMissingNode()).as(key).isFalse());
        ANSWERS.forEach((key, schema) -> assertThat(refs(operation(key))).as(key).contains(schema));
    }

    @Test
    void eachRouteGroupUsesOnlyItsOwnSchemas() {
        // arch 09 §1.1: separate DTOs per route group, always - for every tournament operation, Amr's writes included.
        contract.path("paths").properties().forEach(path -> {
            if (!path.getKey().contains("/tournaments")) {
                return;
            }
            path.getValue().properties().forEach(method -> {
                String key = method.getKey() + " " + path.getKey();
                String prefix = path.getKey().startsWith("/api/v1/customer/") ? "Customer"
                        : path.getKey().startsWith("/api/v1/venue/") ? "Venue" : "Admin";
                assertThat(refs(method.getValue())).as(key)
                        .map(ref -> ref.replaceFirst("^DataResponse", "").replaceFirst("^PageView", ""))
                        .allMatch(ref -> ref.startsWith(prefix) || SHARED.contains(ref) || ref.isEmpty());
            });
        });
    }

    @Test
    void listsArePagedAndDetailsAnswerNotFound() {
        // arch 09 §1.2 (cursor paging); FR-TRN-07: what a caller may not see is NOT_FOUND, never FORBIDDEN.
        ANSWERS.forEach((key, schema) -> {
            if (schema.endsWith("Listing")) {
                assertThat(parameterNames(operation(key))).as(key).contains("cursor", "limit");
            } else {
                assertThat(codes(operation(key), "404")).as(key).containsExactly("NOT_FOUND");
                // The only 403 is the route group's own audience check (arch 09 §1.1), never a visibility refusal.
                assertThat(codes(operation(key), "403")).as(key).containsOnly("FORBIDDEN");
            }
        });
        assertThat(parameterNames(operation("get /api/v1/customer/tournaments"))).contains("areaId", "activity");
    }

    @Test
    void noReadAnswersAContactDetailOrTheCauseOfIneligibility() {
        // arch 09 §1.1 (contact visibility), arch 09 §3.4 + PCR-001 (INELIGIBLE's cause is never a public token).
        ANSWERS.keySet().forEach(key -> {
            List<String> answered = refs(operation(key));
            answered.forEach(name -> assertThat(properties(schema(name))).as(key + " " + name)
                    .doesNotContain("phone", "phoneE164", "email", "ineligibleReason", "ineligibilityCause",
                            "ownerPassword"));
        });
    }

    @Test
    void onlyTheCreatorAndTheVenueGetTheInviteCode() {
        // FR-TRN-06, FR-TRN-07: Discover rows never carry the code; the detail answers it (null to non-creators).
        assertThat(properties(schema("CustomerTournamentListing"))).doesNotContain("inviteCode");
        assertThat(properties(schema("CustomerTournamentDetail"))).contains("inviteCode", "participants");
        assertThat(properties(schema("VenueTournamentDetail"))).contains("inviteCode", "participants");
    }

    private JsonNode operation(String key) {
        String[] parts = key.split(" ", 2);
        return contract.path("paths").path(parts[1]).path(parts[0]);
    }

    private JsonNode schema(String name) {
        return contract.path("components").path("schemas").path(name);
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
