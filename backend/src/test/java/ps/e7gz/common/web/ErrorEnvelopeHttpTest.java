package ps.e7gz.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.RepoFiles;

/**
 * The error envelope end to end (arch 09 §1.3–§1.5): the real Spring MVC stack with the correlation filter, a fixed
 * clock, and failures raised by a test-only controller — including a real guard exception from the database, an
 * unknown guard, an exclusion constraint, and {@code 40001} after {@code CommandRunner} exhausts its retries.
 *
 * <p>Every body is checked for the absence of prose: no {@code message}, no exception text, no SQL (ADR-021).
 */
@Import({ErrorEnvelopeHttpTest.Probe.class, ErrorEnvelopeHttpTest.FixedClock.class})
class ErrorEnvelopeHttpTest extends IntegrationTest {

    static final Instant NOW = Instant.parse("2026-10-12T17:04:11Z");
    static final String BASE = "/test/e7-12";
    static final AtomicInteger RETRY_ATTEMPTS = new AtomicInteger();

    @Autowired
    WebApplicationContext context;

    @Autowired
    CorrelationIdFilter correlationIdFilter;

    @Autowired
    SecurityErrorHandlers securityErrors;

    @Autowired
    ObjectMapper json;

    MockMvc mvc;

    @BeforeEach
    void mvc() {
        // The real MVC stack and the correlation filter; the security chain is E7-14's and not under test here.
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(correlationIdFilter).build();
    }

    // ------------------------------------------------------------------------------------------ success + meta

    @Test
    void successCarriesTheCorrelationIdEverywhereAndServerTimeFromTheClock() throws Exception {
        Response r = call(get(BASE + "/ok"));

        assertThat(r.status()).isEqualTo(200);
        UUID id = UUID.fromString(r.header());
        assertThat(r.body().path("meta").path("correlationId").asText()).isEqualTo(id.toString());
        assertThat(r.body().path("meta").path("serverTime").asText()).isEqualTo("2026-10-12T17:04:11Z");
        assertThat(r.body().path("data").path("seenByTheRequest").asText()).isEqualTo(id.toString());
        assertThat(r.body().path("data").path("inTheLogContext").asText()).isEqualTo(id.toString());
        assertThat(MDC.get(CorrelationId.MDC_KEY)).as("cleared after the request").isNull();
        assertThat(CorrelationId.current()).isEmpty();
    }

    @Test
    void aClientUuidIsKeptAndAnythingElseIsReplaced() throws Exception {
        UUID mine = UUID.randomUUID();
        assertThat(call(get(BASE + "/ok").header(CorrelationId.HEADER, mine.toString())).header()).isEqualTo(mine.toString());

        Response replaced = call(get(BASE + "/ok").header(CorrelationId.HEADER, "abc\r\nSet-Cookie: x"));
        assertThat(UUID.fromString(replaced.header())).isNotNull();
        assertThat(replaced.body().path("meta").path("correlationId").asText()).isEqualTo(replaced.header());
    }

    @Test
    void concurrentRequestsNeverSeeEachOthersId() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<UUID> ids = new ArrayList<>();
            List<Future<Response>> responses = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                UUID id = UUID.randomUUID();
                ids.add(id);
                boolean fails = i % 2 == 0;
                responses.add(pool.submit(() -> call(get(BASE + (fails ? "/boom" : "/ok"))
                        .header(CorrelationId.HEADER, id.toString()))));
            }
            for (int i = 0; i < ids.size(); i++) {
                Response r = responses.get(i).get();
                assertThat(r.header()).isEqualTo(ids.get(i).toString());
                assertThat(r.body().path("meta").path("correlationId").asText()).isEqualTo(ids.get(i).toString());
                if (r.status() == 200) {
                    assertThat(r.body().path("data").path("seenByTheRequest").asText()).isEqualTo(ids.get(i).toString());
                }
            }
            // A pooled thread that served a request carries nothing into the next task.
            assertThat(pool.submit(() -> CorrelationId.current().isEmpty() && MDC.get(CorrelationId.MDC_KEY) == null)
                    .get()).isTrue();
        } finally {
            pool.shutdown();
        }
    }

    // ------------------------------------------------------------------------------------------ coded failures

    @Test
    void anApplicationRefusalIsItsCodeFieldAndDetails() throws Exception {
        Response r = call(get(BASE + "/api-exception"));

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.body().path("error").path("code").asText()).isEqualTo("BOOKING_LEAD_TIME_TOO_SHORT");
        assertThat(r.body().path("error").path("field").asText()).isEqualTo("startAt");
        assertThat(r.body().path("error").path("details").path("minimumLeadMinutes").asInt()).isEqualTo(15);
        assertThat(r.body().path("meta").path("correlationId").asText()).isEqualTo(r.header());
        assertNoProse(r);
    }

    @Test
    void invalidBodiesAndParametersAreValidationFailed() throws Exception {
        Response both = call(post(BASE + "/body").contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertThat(both.status()).isEqualTo(400);
        assertThat(code(both)).isEqualTo("VALIDATION_FAILED");
        assertThat(both.body().path("error").path("details").path("fields").toString()).isEqualTo("[\"count\",\"name\"]");
        assertNoProse(both);

        Response one = call(post(BASE + "/body").contentType(MediaType.APPLICATION_JSON).content("{\"count\":1}"));
        assertThat(one.body().path("error").path("field").asText()).isEqualTo("name");

        Response unreadable = call(post(BASE + "/body").contentType(MediaType.APPLICATION_JSON).content("{not json"));
        assertThat(unreadable.status()).isEqualTo(400);
        assertThat(code(unreadable)).isEqualTo("VALIDATION_FAILED");
        assertNoProse(unreadable);

        Response mismatch = call(get(BASE + "/param").param("count", "abc"));
        assertThat(mismatch.status()).isEqualTo(400);
        assertThat(mismatch.body().path("error").path("field").asText()).isEqualTo("count");

        // A missing required input names itself in error.field, like an invalid one (arch 09 section 1.3).
        Response missingParam = call(get(BASE + "/param"));
        assertThat(missingParam.status()).isEqualTo(400);
        assertThat(code(missingParam)).isEqualTo("VALIDATION_FAILED");
        assertThat(missingParam.body().path("error").path("field").asText()).isEqualTo("count");
        assertMatchesContract(missingParam.body(), "ErrorResponse");

        Response missingHeader = call(get(BASE + "/header"));
        assertThat(missingHeader.status()).isEqualTo(400);
        assertThat(missingHeader.body().path("error").path("field").asText()).isEqualTo("X-Probe");
        assertThat(call(post(BASE + "/ok")).status()).as("method not supported").isEqualTo(400);
    }

    @Test
    void unknownRoutesAndSecurityRefusalsUseTheGeneralCodes() throws Exception {
        Response missing = call(get(BASE + "/no-such-route"));
        assertThat(missing.status()).isEqualTo(404);
        assertThat(code(missing)).isEqualTo("NOT_FOUND");

        Response denied = call(get(BASE + "/denied"));
        assertThat(denied.status()).isEqualTo(403);
        assertThat(code(denied)).isEqualTo("FORBIDDEN");
        assertNoProse(denied);
    }

    @Test
    void aContractStubStays501WithoutABody() throws Exception {
        Response r = call(get(BASE + "/stub"));
        assertThat(r.status()).isEqualTo(501);
        assertThat(r.raw()).isEmpty();
        assertThat(r.header()).isNotBlank();
    }

    // ------------------------------------------------------------------------------------------ database

    @Test
    void aRealGuardExceptionReachesTheClientAsItsDocumentedCode() throws Exception {
        Response r = call(get(BASE + "/guard"));

        assertThat(r.status()).isEqualTo(422);
        assertThat(code(r)).isEqualTo("AREA_HAS_ACTIVE_VENUES");
        assertThat(r.body().path("meta").path("correlationId").asText()).isEqualTo(r.header());
        assertThat(r.raw()).doesNotContain("Area cannot be deactivated").doesNotContain("gd_");
        assertNoProse(r);
    }

    @Test
    void aKnownExclusionConstraintIsASlotConflict() throws Exception {
        Response r = call(get(BASE + "/double-booking"));
        assertThat(r.status()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("SLOT_NO_LONGER_AVAILABLE");
        assertThat(r.raw()).doesNotContain("ex_no_double_booking");
    }

    /**
     * The two identified guards that are unmapped by design (unreachable through a valid user action; see
     * GuardErrorIdentifiersTest "reachability"): fired for real in the database, they reach the client as the
     * meta-only 500 — never as a guessed code — and are logged as defects with their identifier.
     */
    @Test
    void theTwoDefectOnlyGuardsReachTheClientAsTheMetaOnly500() throws Exception {
        Logger handlerLog = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        handlerLog.addAppender(logs);
        try {
            for (String[] c : List.of(new String[] {"/closure-conflict", "gd_reservation_conflict_closure"},
                    new String[] {"/area-inactive", "gd_online_booking_area_inactive"})) {
                logs.list.clear();
                Response r = call(get(BASE + c[0]));
                assertUnexpected(r);
                assertThat(r.raw()).doesNotContain("gd_").doesNotContain("Closure").doesNotContain("Area");
                assertThat(logs.list).singleElement().satisfies(e -> {
                    assertThat(e.getLevel().toString()).isEqualTo("ERROR");
                    assertThat(e.getFormattedMessage()).contains("sqlState=P0001").contains("constraint=" + c[1]);
                    assertThat(e.getMDCPropertyMap()).containsEntry(CorrelationId.MDC_KEY, r.header());
                });
            }
        } finally {
            handlerLog.detachAppender(logs);
        }
    }

    @Test
    void anUnknownGuardIsA500WithOnlyTheCorrelationId() throws Exception {
        assertUnexpected(call(get(BASE + "/unknown-guard")));
    }

    @Test
    void anUnexpectedExceptionIsA500WithOnlyTheCorrelationId() throws Exception {
        assertUnexpected(call(get(BASE + "/boom")));
    }

    /** 3A: the runner's retries are the only ones; once exhausted the client gets the unexpected 500, not a conflict. */
    @Test
    void exhaustedSerializationRetriesAreA500NotASlotConflict() throws Exception {
        RETRY_ATTEMPTS.set(0);
        Response r = call(get(BASE + "/retry-exhausted"));

        assertUnexpected(r);
        assertThat(RETRY_ATTEMPTS.get()).as("CommandRunner's own attempts, no more").isEqualTo(5);
    }

    // ------------------------------------------------------------------------------------------ method validation

    /** Invalid request input through built-in MVC method validation is the client's 400. */
    @Test
    void anInvalidRequestParameterIsValidationFailed() throws Exception {
        Response r = call(get(BASE + "/param-constraint").param("count", "0"));
        assertThat(r.status()).isEqualTo(400);
        assertThat(code(r)).isEqualTo("VALIDATION_FAILED");
        assertThat(r.body().path("error").path("field").asText()).isEqualTo("count");
        assertMatchesContract(r.body(), "ErrorResponse");
    }

    /** An invalid controller return value is the server's defect: the meta-only 500, never a 400. */
    @Test
    void anInvalidReturnValueIsTheMetaOnly500() throws Exception {
        Response r = call(get(BASE + "/bad-return"));
        assertUnexpected(r);
        assertMatchesContract(r.body(), "UnexpectedErrorResponse");
    }

    /** A constraint violated inside a service (not request input) is a server defect too. */
    @Test
    void aServiceConstraintViolationIsTheMetaOnly500() throws Exception {
        assertUnexpected(call(get(BASE + "/service-violation")));
    }

    /** A violation of a controller method's parameters (AOP method validation) is request input: 400. */
    @Test
    void aControllerParameterConstraintViolationIsValidationFailed() throws Exception {
        Response r = call(get(BASE + "/controller-parameter-violation"));
        assertThat(r.status()).isEqualTo(400);
        assertThat(code(r)).isEqualTo("VALIDATION_FAILED");
        assertThat(r.body().path("error").path("field").asText()).isEqualTo("count");
    }

    // ------------------------------------------------------------------------------------------ JSON vs contract

    /**
     * The serialized JSON agrees with openapi.json: {@code field} and {@code details} are optional and not nullable
     * ({@code field?: string} in the generated TypeScript), so when absent they are omitted, never {@code null}.
     */
    @Test
    void aCodedErrorWithoutFieldOrDetailsOmitsThem() throws Exception {
        Response bare = call(get(BASE + "/api-exception-bare"));
        assertThat(bare.status()).isEqualTo(404);
        assertThat(names(bare.body().path("error"))).containsExactly("code");
        assertThat(bare.raw()).doesNotContain("null");
        assertMatchesContract(bare.body(), "ErrorResponse");

        Response full = call(get(BASE + "/api-exception"));
        assertThat(names(full.body().path("error"))).containsExactlyInAnyOrder("code", "field", "details");
        assertMatchesContract(full.body(), "ErrorResponse");

        Response many = call(post(BASE + "/body").contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertThat(names(many.body().path("error"))).containsExactlyInAnyOrder("code", "details");
        assertMatchesContract(many.body(), "ErrorResponse");

        assertMatchesContract(call(get(BASE + "/boom")).body(), "UnexpectedErrorResponse");
        assertMatchesContract(call(get(BASE + "/ok")).body().path("meta"), "ResponseMeta");
    }

    // ------------------------------------------------------------------------------------------ security edge

    @Test
    void securityChainFailuresUseTheEnvelopeAndTheRequestCorrelationId() throws Exception {
        UUID id = UUID.randomUUID();
        try (CorrelationId.Scope ignored = CorrelationId.open(id)) {
            MockHttpServletResponse unauthenticated = new MockHttpServletResponse();
            securityErrors.authenticationEntryPoint()
                    .commence(new MockHttpServletRequest(), unauthenticated, new BadCredentialsException("secret"));
            JsonNode body = json.readTree(unauthenticated.getContentAsString());
            assertThat(unauthenticated.getStatus()).isEqualTo(401);
            assertThat(names(body.path("error"))).containsExactly("code");
            assertMatchesContract(body, "ErrorResponse");
            assertThat(body.path("error").path("code").asText()).isEqualTo("UNAUTHENTICATED");
            assertThat(body.path("meta").path("correlationId").asText()).isEqualTo(id.toString());
            assertThat(unauthenticated.getContentAsString()).doesNotContain("secret");

            MockHttpServletResponse forbidden = new MockHttpServletResponse();
            securityErrors.accessDeniedHandler()
                    .handle(new MockHttpServletRequest(), forbidden, new AccessDeniedException("secret"));
            assertThat(forbidden.getStatus()).isEqualTo(403);
            JsonNode forbiddenBody = json.readTree(forbidden.getContentAsString());
            assertThat(forbiddenBody.path("error").path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(names(forbiddenBody.path("error"))).containsExactly("code");
            assertMatchesContract(forbiddenBody, "ErrorResponse");
        }
    }

    @Test
    void theCorrelationFilterRunsBeforeTheSecurityChain() {
        int order = CorrelationIdFilter.class.getAnnotation(Order.class).value();
        assertThat(order).isLessThan(SecurityProperties.DEFAULT_FILTER_ORDER);
    }

    // ================================================================================================ helpers

    record Response(int status, String header, String raw, JsonNode body) {
    }

    private Response call(RequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        String raw = result.getResponse().getContentAsString();
        JsonNode body = raw.isEmpty() ? json.nullNode() : json.readTree(raw);
        return new Response(result.getResponse().getStatus(), result.getResponse().getHeader(CorrelationId.HEADER), raw, body);
    }

    private static List<String> names(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }

    /**
     * Checks a JSON value against a schema of the committed openapi.json: required properties present, no undeclared
     * property, no {@code null} unless the schema allows it, strings where strings are declared, nested references
     * followed. Enough to catch the serialization drifting from the contract the TypeScript client is generated from.
     */
    private void assertMatchesContract(JsonNode value, String schemaName) throws Exception {
        JsonNode schemas = json.readTree(Files.readString(RepoFiles.get("packages/api-client/openapi.json")))
                .path("components").path("schemas");
        assertMatches(value, schemas.path(schemaName), schemas, schemaName);
    }

    private static void assertMatches(JsonNode value, JsonNode schema, JsonNode schemas, String where) {
        if (schema.has("$ref")) {
            String ref = schema.path("$ref").asText();
            assertMatches(value, schemas.path(ref.substring(ref.lastIndexOf('/') + 1)), schemas, where);
            return;
        }
        assertThat(schema.isMissingNode()).as(where + ": schema exists").isFalse();
        boolean nullable = schema.path("type").toString().contains("null") || schema.path("anyOf").toString().contains("null");
        if (value.isNull()) {
            assertThat(nullable).as(where + " is null but the contract does not allow null").isTrue();
            return;
        }
        String type = schema.path("type").isArray() ? schema.path("type").get(0).asText() : schema.path("type").asText();
        if (schema.has("enum") || "string".equals(type)) {
            assertThat(value.isTextual()).as(where + " is a string").isTrue();
            if (schema.has("enum")) {
                List<String> allowed = new ArrayList<>();
                schema.path("enum").forEach(v -> allowed.add(v.asText()));
                assertThat(allowed).as(where + " value").contains(value.asText());
            }
        } else if ("object".equals(type)) {
            assertThat(value.isObject()).as(where + " is an object").isTrue();
            schema.path("required").forEach(r -> assertThat(value.has(r.asText())).as(where + "." + r.asText() + " required").isTrue());
            JsonNode properties = schema.path("properties");
            if (properties.isObject() && properties.size() > 0 && !schema.path("additionalProperties").asBoolean(false)) {
                value.properties().forEach(p -> {
                    assertThat(properties.has(p.getKey())).as(where + "." + p.getKey() + " is declared").isTrue();
                    assertMatches(p.getValue(), properties.path(p.getKey()), schemas, where + "." + p.getKey());
                });
            }
        }
    }

    private static String code(Response r) {
        return r.body().path("error").path("code").asText();
    }

    private static void assertUnexpected(Response r) {
        assertThat(r.status()).isEqualTo(500);
        assertThat(r.body().properties()).extracting(Map.Entry::getKey).containsExactly("meta");
        assertThat(r.body().path("meta").path("correlationId").asText()).isEqualTo(r.header());
        assertThat(r.body().path("meta").path("serverTime").asText()).isEqualTo("2026-10-12T17:04:11Z");
        assertThat(r.raw()).doesNotContain("secret").doesNotContain("SELECT").doesNotContain("Exception")
                .doesNotContain("SLOT_NO_LONGER_AVAILABLE").doesNotContain("40001");
    }

    private static void assertNoProse(Response r) {
        assertThat(r.raw()).doesNotContain("\"message\"").doesNotContain("secret").doesNotContain("Exception")
                .doesNotContain("\"trace\"");
    }

    // ================================================================================================ fixtures

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    record Body(@NotBlank String name, @NotNull Integer count) {
    }

    @RestController
    @RequestMapping(BASE)
    static class Probe {

        private final ResponseMetaFactory meta;
        private final CommandRunner runner;
        private final Validator validator;
        /** Owner-rights connection to the shared test database; every write below is rolled back. */
        private final TransactionTemplate owner;
        private final JdbcTemplate ownerJdbc;

        Probe(ResponseMetaFactory meta, CommandRunner runner, Validator validator) {
            this.meta = meta;
            this.runner = runner;
            this.validator = validator;
            DriverManagerDataSource ds = new DriverManagerDataSource(
                    PostgresTestContainer.jdbcUrl(DATABASE), "postgres", "postgres");
            this.owner = new TransactionTemplate(new DataSourceTransactionManager(ds));
            this.ownerJdbc = new JdbcTemplate(ds);
        }

        @GetMapping("/ok")
        DataResponse<Map<String, String>> ok() {
            return meta.data(Map.of("seenByTheRequest", CorrelationId.require().toString(),
                    "inTheLogContext", MDC.get(CorrelationId.MDC_KEY)));
        }

        @GetMapping("/api-exception-bare")
        void apiExceptionBare() {
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        }

        @GetMapping("/param-constraint")
        void paramConstraint(@RequestParam @Min(1) int count) {
        }

        /** Method-level constraint = return-value constraint; the empty string violates it. */
        @GetMapping("/bad-return")
        @NotBlank
        String badReturn() {
            return "";
        }

        /** A service's bean validation fails: not request input. */
        @GetMapping("/service-violation")
        void serviceViolation() {
            Set<ConstraintViolation<Body>> violations = validator.validate(new Body(null, null));
            throw new ConstraintViolationException(violations);
        }

        /** AOP-style method validation of this controller's own parameter (root bean: this controller). */
        @GetMapping("/controller-parameter-violation")
        void controllerParameterViolation() throws NoSuchMethodException {
            Method method = Probe.class.getDeclaredMethod("paramConstraint", int.class);
            Set<ConstraintViolation<Probe>> violations =
                    validator.forExecutables().validateParameters(this, method, new Object[] {0});
            throw new ConstraintViolationException(violations);
        }

        @GetMapping("/api-exception")
        void apiException() {
            throw new ApiException(ApiErrorCode.BOOKING_LEAD_TIME_TOO_SHORT, "startAt", Map.of("minimumLeadMinutes", 15));
        }

        @PostMapping("/body")
        void body(@Valid @RequestBody Body body) {
        }

        @GetMapping("/param")
        void param(@RequestParam int count) {
        }

        @GetMapping("/header")
        void header(@RequestHeader("X-Probe") String probe) {
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("secret SELECT password_hash FROM iam.user_account");
        }

        @GetMapping("/stub")
        void stub() {
            throw NotImplementedYet.stub();
        }

        @GetMapping("/denied")
        void denied() {
            throw new AccessDeniedException("secret");
        }

        /** geo.guard_area_deactivation: an Area with an ACTIVE Venue cannot be deactivated. */
        @GetMapping("/guard")
        void guard() {
            UUID area = UUID.randomUUID();
            owner.executeWithoutResult(tx -> {
                ownerJdbc.update("INSERT INTO geo.area(id,name,is_active) VALUES (?,?,true)", area, "e712-" + area);
                ownerJdbc.update("INSERT INTO venue.venue(id,area_id,name,address,location,marketplace_status,timezone,phone) "
                        + "VALUES (?,?,'V','a',ST_GeogFromText('POINT(31 30)'),'ACTIVE','Africa/Cairo','+20')",
                        UUID.randomUUID(), area);
                ownerJdbc.update("UPDATE geo.area SET is_active=false WHERE id=?", area);
            });
        }

        /** inventory.guard_resource_reservation_cross_class: a CLOSURE row over an active Booking reservation. */
        @GetMapping("/closure-conflict")
        void closureConflict() {
            owner.executeWithoutResult(tx -> {
                UUID resource = UUID.randomUUID();
                ownerJdbc.execute("SET LOCAL session_replication_role = replica");
                ownerJdbc.update("INSERT INTO inventory.resource_reservation(id,resource_id,reservation_type,booking_id,"
                        + "planned_start,planned_end,planned_range,actual_reservation_start,active_flag) VALUES "
                        + "(gen_random_uuid(),?,'BOOKING',gen_random_uuid(),now()+interval '1 day',now()+interval '1 day 2 hours',"
                        + "tstzrange(now()+interval '1 day',now()+interval '1 day 2 hours','[)'),now()+interval '1 day',true)",
                        resource);
                ownerJdbc.execute("SET LOCAL session_replication_role = origin");
                ownerJdbc.execute("CREATE TABLE public.e712_probe (LIKE inventory.resource_reservation INCLUDING DEFAULTS)");
                ownerJdbc.execute("CREATE TRIGGER t BEFORE INSERT ON public.e712_probe FOR EACH ROW "
                        + "EXECUTE FUNCTION inventory.guard_resource_reservation_cross_class()");
                ownerJdbc.update("INSERT INTO public.e712_probe(id,resource_id,reservation_type,planned_start,planned_end,"
                        + "planned_range,actual_reservation_start,active_flag) VALUES (gen_random_uuid(),?,'CLOSURE',"
                        + "now()+interval '1 day',now()+interval '1 day 4 hours',tstzrange(now()+interval '1 day',"
                        + "now()+interval '1 day 4 hours','[)'),now()+interval '1 day',true)", resource);
            });
        }

        /** booking.validate_online_booking_intake: an Online Booking at a Venue whose Area is inactive. */
        @GetMapping("/area-inactive")
        void areaInactive() {
            owner.executeWithoutResult(tx -> {
                UUID area = UUID.randomUUID();
                UUID venue = UUID.randomUUID();
                UUID template = UUID.randomUUID();
                ownerJdbc.update("INSERT INTO geo.area(id,name,is_active) VALUES (?,?,true)", area, "e712-" + area);
                ownerJdbc.update("INSERT INTO venue.venue(id,area_id,name,address,location,marketplace_status,timezone,phone) "
                        + "VALUES (?,?,'V','a',ST_GeogFromText('POINT(31 30)'),'ONBOARDING','Africa/Cairo','+20')", venue, area);
                ownerJdbc.update("UPDATE geo.area SET is_active=false WHERE id=?", area);
                ownerJdbc.update("INSERT INTO venue.venue_opening_template(id,venue_id,default_local_open,default_local_close,"
                        + "default_full_day,version,updated_at) VALUES (?,?,'00:00','00:00',true,0,now())", template, venue);
                ownerJdbc.update("INSERT INTO venue.operating_session(id,venue_id,opening_template_id,operating_day,local_start,"
                        + "local_end,session_period,timezone,generated_from_version,generated_at) VALUES (gen_random_uuid(),?,?,"
                        + "(now() AT TIME ZONE 'Africa/Cairo')::date,(now() AT TIME ZONE 'Africa/Cairo')-interval '2 hours',"
                        + "(now() AT TIME ZONE 'Africa/Cairo')+interval '6 hours',tstzrange(now()-interval '2 hours',"
                        + "now()+interval '6 hours','[)'),'Africa/Cairo',0,now())", venue, template);
                ownerJdbc.execute("CREATE TABLE public.e712_probe (LIKE booking.booking INCLUDING DEFAULTS)");
                ownerJdbc.execute("DO $d$ DECLARE c text; BEGIN FOR c IN SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='e712_probe' AND is_nullable='NO' LOOP "
                        + "EXECUTE format('ALTER TABLE public.e712_probe ALTER COLUMN %I DROP NOT NULL',c); END LOOP; END $d$");
                ownerJdbc.execute("CREATE TRIGGER t BEFORE INSERT ON public.e712_probe FOR EACH ROW "
                        + "EXECUTE FUNCTION booking.validate_online_booking_intake()");
                ownerJdbc.update("INSERT INTO public.e712_probe(id,venue_id,source,status,created_at,start_at,"
                        + "venue_response_expires_at,booking_fee_snapshot) VALUES (gen_random_uuid(),?,'ONLINE','PENDING',now(),"
                        + "now()+interval '30 minutes',now()+interval '10 minutes',0)", venue);
            });
        }

        @GetMapping("/unknown-guard")
        void unknownGuard() {
            owner.executeWithoutResult(tx -> ownerJdbc.execute(
                    "DO $$ BEGIN RAISE EXCEPTION 'secret rule text' USING CONSTRAINT = 'gd_not_in_the_mapping'; END $$"));
        }

        @GetMapping("/double-booking")
        void doubleBooking() {
            owner.executeWithoutResult(tx -> {
                ownerJdbc.execute("SET LOCAL session_replication_role = replica");
                String insert = "INSERT INTO inventory.resource_reservation(id,resource_id,reservation_type,booking_id,"
                        + "planned_start,planned_end,planned_range,actual_reservation_start,active_flag) VALUES "
                        + "(gen_random_uuid(),?,'BOOKING',gen_random_uuid(),now()+interval '1 day',now()+interval '1 day 2 hours',"
                        + "tstzrange(now()+interval '1 day',now()+interval '1 day 2 hours','[)'),now()+interval '1 day',true)";
                UUID resource = UUID.randomUUID();
                ownerJdbc.update(insert, resource);
                ownerJdbc.update(insert, resource);
            });
        }

        @GetMapping("/retry-exhausted")
        void retryExhausted() {
            runner.run(new Command<Void>() {
                @Override
                public EligibilityCategory eligibilityCategory() {
                    return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
                }

                @Override
                public CommandScope scope() {
                    return CommandScope.shared(LockSet.empty());
                }

                @Override
                public Void execute(CommandContext context) {
                    RETRY_ATTEMPTS.incrementAndGet();
                    context.jdbc().sql("DO $$ BEGIN RAISE EXCEPTION 'secret' USING ERRCODE = '40001'; END $$").update();
                    return null;
                }
            });
        }
    }
}
