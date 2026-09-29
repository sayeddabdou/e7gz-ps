package ps.e7gz.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.codes.OpeningDayMode;
import ps.e7gz.common.codes.OperationalRestriction;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.codes.ResourceBaseState;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.support.IntegrationTest;

/**
 * The conventions of arch 09 §1 hold for every operation of the production contract (the Sprint 1 contract, E7-22),
 * and every enum it publishes is spelled exactly as the database CHECK list (CLAUDE.md §5).
 */
@AutoConfigureMockMvc(addFilters = false)
class OpenApiProductionContractTest extends IntegrationTest {

    private static final Set<String> WRITES = Set.of("post", "put", "patch", "delete");

    @Autowired
    MockMvc mvc;

    @Autowired
    DataSource dataSource;

    private JsonNode contract;

    @BeforeEach
    void readContract() throws Exception {
        contract = OpenApiDocument.parse(OpenApiDocument.fetch(mvc));
    }

    /**
     * The only operations without a Bearer token (arch 09 §1.2: registration, login, OTP and public discovery). Every
     * other operation — venue, admin, and any authenticated customer route such as profile or bookings — needs one.
     * Registration and phone OTP are E7-13's; add login/refresh/password reset here when E7-14 lands them.
     */
    static final Set<String> PUBLIC_OPERATIONS = Set.of(
            "post /api/v1/customer/auth/register",
            "post /api/v1/customer/auth/otp/send",
            "post /api/v1/customer/auth/verify-phone",
            "get /api/v1/customer/areas",
            "get /api/v1/customer/venues",
            "get /api/v1/customer/venues/map",
            "get /api/v1/customer/venues/{venueId}",
            "get /api/v1/customer/find-available");

    /** Why an operation breaks the security rule, or null when it follows it. */
    static String securityViolation(String key, JsonNode operation) {
        boolean secured = operation.path("security").toString().contains("bearerAuth");
        if (PUBLIC_OPERATIONS.contains(key)) {
            return secured ? key + " is public discovery but requires a token" : null;
        }
        if (!secured) {
            return key + " is not a public operation but has no Bearer requirement";
        }
        return operation.path("responses").has("401") ? null : key + " is secured but documents no 401";
    }

    @Test
    void onlyTheListedPublicOperationsGoWithoutTheBearerToken() {
        Map<String, JsonNode> operations = operations();

        assertThat(operations.keySet()).containsAll(PUBLIC_OPERATIONS);
        operations.forEach((key, operation) -> assertThat(securityViolation(key, operation)).as(key).isNull());
    }

    @Test
    void anAuthenticatedCustomerRouteIsAllowedAndMustBeSecured() throws Exception {
        JsonNode securedProfile = OpenApiDocument.parse(
                "{\"security\":[{\"bearerAuth\":[]}],\"responses\":{\"200\":{},\"401\":{}}}");
        JsonNode publicProfile = OpenApiDocument.parse("{\"responses\":{\"200\":{}}}");

        assertThat(securityViolation("get /api/v1/customer/profile", securedProfile)).isNull();
        assertThat(securityViolation("post /api/v1/customer/bookings", securedProfile)).isNull();
        assertThat(securityViolation("get /api/v1/customer/profile", publicProfile)).contains("no Bearer requirement");
        assertThat(securityViolation("get /api/v1/admin/areas", publicProfile)).contains("no Bearer requirement");
        assertThat(securityViolation("get /api/v1/customer/areas", securedProfile)).contains("requires a token");
    }

    @Test
    void everyOperationTakesACorrelationIdAndEveryWriteAnIdempotencyKey() {
        operations().forEach((key, operation) -> {
            List<String> headers = headerNames(operation);
            assertThat(headers).as(key).contains("X-Correlation-Id");
            assertThat(headers).as(key + ": each header once").doesNotHaveDuplicates();
            if (WRITES.contains(key.split(" ")[0])) {
                assertThat(headers).as(key).contains("Idempotency-Key");
            }
            operation.path("responses").forEach(response ->
                    assertThat(response.path("headers").has("X-Correlation-Id")).as(key).isTrue());
        });
    }

    @Test
    void successBodiesUseTheDataEnvelopeAndErrorsTheErrorEnvelope() {
        operations().forEach((key, operation) -> operation.path("responses").properties().forEach(entry -> {
            JsonNode schema = entry.getValue().path("content").path("application/json").path("schema");
            if (schema.isMissingNode()) {
                assertThat(entry.getKey()).as(key + " without a body").isIn("204", "501");
                return;
            }
            JsonNode resolved = resolve(schema);
            if (entry.getKey().equals("500")) {
                // An unexpected failure carries only meta (arch 09 §1.4): no code, no message.
                assertThat(schema.path("$ref").asText()).as(key).isEqualTo("#/components/schemas/UnexpectedErrorResponse");
                assertThat(fieldNames(resolved.path("required"))).as(key).containsExactly("meta");
                assertThat(resolved.path("properties").has("error")).as(key).isFalse();
            } else if (entry.getKey().startsWith("2")) {
                assertThat(fieldNames(resolved.path("required"))).as(key).containsExactlyInAnyOrder("data", "meta");
            } else {
                // The shared envelope, with error.code narrowed to exactly the codes the operation declares.
                assertThat(schema.path("allOf").get(0).path("$ref").asText()).as(key + " " + entry.getKey())
                        .isEqualTo("#/components/schemas/ErrorResponse");
                List<String> codes = fieldNames(schema.path("allOf").get(1)
                        .path("properties").path("error").path("properties").path("code").path("enum"));
                assertThat(codes).as(key + " " + entry.getKey()).isNotEmpty()
                        .isSubsetOf(fieldNames(contract.path("components").path("schemas").path("ApiErrorCode").path("enum")));
                assertThat(entry.getValue().path("description").asText()).isEqualTo("error.code: " + String.join(" | ", codes));
            }
        }));
    }

    @Test
    void everyOperationDocumentsTheGeneralFailures() {
        operations().forEach((key, operation) -> {
            JsonNode responses = operation.path("responses");
            assertThat(narrowedCodes(responses.path("429"))).as(key).contains("RATE_LIMITED");
            assertThat(responses.has("500")).as(key).isTrue();
            if (operation.path("security").toString().contains("bearerAuth")) {
                assertThat(narrowedCodes(responses.path("401"))).as(key).contains("UNAUTHENTICATED");
                assertThat(narrowedCodes(responses.path("403"))).as(key).contains("FORBIDDEN");
            }
        });
    }

    @Test
    void declaredErrorCodesAreNarrowedPerStatus() {
        JsonNode responses = operations().get("post /api/v1/venue/resources/{resourceId}/disable").path("responses");

        assertThat(narrowedCodes(responses.path("422"))).containsExactly("RESOURCE_HAS_BLOCKING_RESERVATIONS");
        assertThat(narrowedCodes(responses.path("423"))).containsExactly("OWNER_PASSWORD_REQUIRED", "OWNER_PASSWORD_INVALID");
        // The general codes approved in E7-12 (arch 09 §1.5) on every secured operation with a path parameter.
        assertThat(narrowedCodes(responses.path("401"))).containsExactly("UNAUTHENTICATED");
        assertThat(narrowedCodes(responses.path("403"))).containsExactly("FORBIDDEN");
        assertThat(narrowedCodes(responses.path("404"))).containsExactly("NOT_FOUND");
        assertThat(narrowedCodes(responses.path("400"))).containsExactly("VALIDATION_FAILED");
        assertThat(narrowedCodes(responses.path("429"))).containsExactly("RATE_LIMITED");
        // The envelope keeps its meta and details.
        JsonNode envelope = contract.path("components").path("schemas");
        assertThat(fieldNames(envelope.path("ErrorResponse").path("required"))).containsExactlyInAnyOrder("error", "meta");
        assertThat(envelope.path("ErrorBody").path("properties").path("details").path("additionalProperties").asBoolean()).isTrue();
    }

    private static List<String> narrowedCodes(JsonNode response) {
        return fieldNames(response.path("content").path("application/json").path("schema").path("allOf").get(1)
                .path("properties").path("error").path("properties").path("code").path("enum"));
    }

    @Test
    void ownerPasswordTravelsInTheBodyAndCanBeRefusedWith423() {
        List<String> withOwnerPassword = new ArrayList<>();
        operations().forEach((key, operation) -> {
            JsonNode body = operation.path("requestBody").path("content").path("application/json").path("schema");
            if (!body.isMissingNode() && resolve(body).path("properties").has("ownerPassword")) {
                withOwnerPassword.add(key);
                assertThat(operation.path("responses").has("423")).as(key).isTrue();
                assertThat(resolve(body).path("properties").path("ownerPassword").path("writeOnly").asBoolean()).isTrue();
            }
            assertThat(headerNames(operation)).as("never a header (ADR-011): " + key)
                    .noneMatch(name -> name.toLowerCase().contains("owner"));
        });
        // FR-VEN-03's protected Sprint 1 actions.
        assertThat(withOwnerPassword).contains(
                "post /api/v1/venue/resources/{resourceId}/disable",
                "post /api/v1/venue/resources/{resourceId}/re-enable",
                "post /api/v1/venue/resources/{resourceId}/archive",
                "post /api/v1/venue/resources/{resourceId}/restore",
                "post /api/v1/venue/resources/{resourceId}/console",
                "put /api/v1/venue/pricing",
                "put /api/v1/venue/resources/{resourceId}/price-override",
                "delete /api/v1/venue/resources/{resourceId}/price-override",
                "post /api/v1/venue/opening-hours/commit");
    }

    @Test
    void nullableReferencesAreWrittenAsAnyOfWithNull() {
        List<String> nullableRefs = new ArrayList<>();
        contract.path("components").path("schemas").properties().forEach(schema ->
                schema.getValue().path("properties").properties().forEach(property -> {
                    JsonNode value = property.getValue();
                    String where = schema.getKey() + "." + property.getKey();
                    // {"$ref": X, "type": "null"} only matches null in OpenAPI 3.1.
                    assertThat(value.has("$ref") && value.path("type").toString().contains("null")).as(where).isFalse();
                    if (value.path("anyOf").toString().contains("\"null\"")) {
                        nullableRefs.add(where);
                    }
                }));
        assertThat(nullableRefs).contains("CustomerVenueCard.startingPrice", "VenueResource.consoleType",
                "AdminArea.centroid");
    }

    @Test
    void publishedEnumsAreSpelledExactlyAsTheDatabaseCheckLists() {
        Map<String, Enum<?>[]> enums = Map.of(
                "Activity", Activity.values(),
                "ConsoleType", ConsoleType.values(),
                "PlayMode", PlayMode.values(),
                "MarketplaceStatus", MarketplaceStatus.values(),
                "VenueAccountStatus", VenueAccountStatus.values(),
                "ResourceBaseState", ResourceBaseState.values(),
                "OperationalRestriction", OperationalRestriction.values(),
                "OpeningDayMode", OpeningDayMode.values());
        Map<String, String> checks = Map.of(
                "Activity", "ck_resource_activity",
                "ConsoleType", "ck_resource_console_type",
                "PlayMode", "ck_default_price_play_mode",
                "MarketplaceStatus", "ck_venue_marketplace_status",
                "VenueAccountStatus", "ck_venue_account_status",
                "ResourceBaseState", "ck_resource_base_state",
                "OperationalRestriction", "ck_resource_restriction",
                "OpeningDayMode", "ck_opening_day_mode");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        enums.forEach((name, values) -> {
            Set<String> java = Arrays.stream(values).map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> published = new LinkedHashSet<>(fieldNames(contract.path("components").path("schemas").path(name).path("enum")));
            String definition = jdbc.queryForObject(
                    "select pg_get_constraintdef(oid) from pg_constraint where conname = ?", String.class, checks.get(name));
            Set<String> database = quotedValues(definition);

            assertThat(published).as(name + " in the contract").isEqualTo(java);
            assertThat(java).as(name + " vs " + checks.get(name)).isEqualTo(database);
        });
    }

    private Map<String, JsonNode> operations() {
        Map<String, JsonNode> operations = new java.util.TreeMap<>();
        contract.path("paths").properties().forEach(path -> path.getValue().properties().forEach(method ->
                operations.put(method.getKey() + " " + path.getKey(), method.getValue())));
        assertThat(operations).isNotEmpty();
        return operations;
    }

    private JsonNode resolve(JsonNode schema) {
        String ref = schema.path("$ref").asText();
        return ref.isEmpty() ? schema : contract.path("components").path("schemas").path(ref.substring(ref.lastIndexOf('/') + 1));
    }

    private static List<String> headerNames(JsonNode operation) {
        List<String> names = new ArrayList<>();
        operation.path("parameters").forEach(parameter -> {
            if ("header".equals(parameter.path("in").asText())) {
                names.add(parameter.path("name").asText());
            }
        });
        return names;
    }

    private static List<String> fieldNames(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static Set<String> quotedValues(String checkDefinition) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("'([A-Za-z0-9_]+)'").matcher(checkDefinition);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }
}
