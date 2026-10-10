package ps.e7gz.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.ResultAggregate;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-42 over HTTP: the {@code Idempotency-Key} header, the error envelope and the replayed response as a client sees
 * them, through the real security chain, exception mapping and database. A test-only write (imported only here, never
 * in the contract) uses the shared {@link Idempotency} API exactly as a feature controller will. Of the real ADR-015
 * operations only creation is implemented (E7-46, tested in its module); the others are still 501 stubs of
 * E7-47/48/49, so for them only the header contract is checked.
 */
@AutoConfigureMockMvc
@Import(IdempotencyHttpTest.Probe.class)
@ExtendWith(OutputCaptureExtension.class)
class IdempotencyHttpTest extends IntegrationTest {

    static final String CUSTOMER_PATH = "/api/v1/customer/test-e7-42/widgets/{widgetId}/orders";
    static final String VENUE_PATH = "/api/v1/venue/test-e7-42/widgets/{widgetId}/orders";
    static final String PROTECTED_PATH = "/api/v1/venue/test-e7-42/widgets/{widgetId}/protected-action";

    static final AtomicInteger EXECUTIONS = new AtomicInteger();
    static final AtomicBoolean FAIL_NEXT = new AtomicBoolean();
    static final String CONTACT_PATH = "/api/v1/customer/test-e7-42/widgets/{widgetId}/contact";
    static final String LOOSE_PATH = "/api/v1/customer/test-e7-42/widgets/{widgetId}/loose";
    /** The retention boundary of the contact probe (stands for e.g. terminalAt + 2h, arch 10 section 5.1). */
    static final AtomicReference<Instant> VISIBLE_UNTIL = new AtomicReference<>(Instant.MAX);
    /** The secret the dynamic-response probe returns; never in a log or a body. */
    static final AtomicReference<String> DYNAMIC_SECRET = new AtomicReference<>("unset");

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ObjectMapper json;

    @Autowired
    DataSource appDataSource;

    String customerToken;
    String otherCustomerToken;
    String venueToken;

    public record OrderRequest(@NotBlank String label, int quantity, Map<String, Object> options) {
    }

    public record OrderView(UUID orderId, UUID widgetId, String label, int quantity) {
    }

    /** An Owner-Password-protected venue action (ADR-011: the PIN travels in the body). */
    public record ProtectedRequest(String reasonText, String ownerPassword, Map<String, Object> options) {
    }

    public record ContactView(UUID bookingId, @JsonInclude(JsonInclude.Include.NON_NULL) String phone) {
    }

    public record LooseView(UUID id, Map<String, Object> details) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class Probe {

        private final Idempotency idempotency;
        private final CommandRunner runner;
        private final ResponseMetaFactory meta;

        Probe(Idempotency idempotency, CommandRunner runner, ResponseMetaFactory meta) {
            this.idempotency = idempotency;
            this.runner = runner;
            this.meta = meta;
        }

        @PostMapping(CUSTOMER_PATH)
        ResponseEntity<DataResponse<OrderView>> customerOrder(
                @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
                @PathVariable UUID widgetId,
                @Valid @RequestBody OrderRequest body,
                @AuthenticationPrincipal AuthenticatedSubject subject) {
            return order("POST " + CUSTOMER_PATH, idempotencyKey, widgetId, body, subject);
        }

        @PostMapping(CONTACT_PATH)
        ResponseEntity<DataResponse<ContactView>> contact(
                @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
                @PathVariable UUID widgetId,
                @AuthenticationPrincipal AuthenticatedSubject subject) {
            // The owning module's concrete rule: phone visible only before the boundary, judged at the command instant.
            StoredResponse<ContactView> response = StoredResponse.of(ContactView.class, 201).replayedThrough(
                    (ctx, stored) -> ctx.commandNow().isBefore(VISIBLE_UNTIL.get())
                            ? stored : new ContactView(stored.bookingId(), null));
            IdempotentResult<ContactView> result = runner.run(idempotency.wrap(
                    idempotency.request(subject, "POST " + CONTACT_PATH, idempotencyKey, Map.of("widgetId", widgetId),
                            null),
                    response, new Mutation<>(() -> new ContactView(UUID.randomUUID(), "+201000000001"))));
            return ResponseEntity.status(result.status()).body(meta.data(result.body()));
        }

        @PostMapping(LOOSE_PATH)
        ResponseEntity<DataResponse<LooseView>> loose(
                @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
                @PathVariable UUID widgetId,
                @AuthenticationPrincipal AuthenticatedSubject subject) {
            IdempotentResult<LooseView> result = runner.run(idempotency.wrap(
                    idempotency.request(subject, "POST " + LOOSE_PATH, idempotencyKey, Map.of("widgetId", widgetId),
                            null),
                    StoredResponse.of(LooseView.class, 201),
                    new Mutation<>(() -> new LooseView(UUID.randomUUID(),
                            Map.of("session", Map.of("refreshToken", DYNAMIC_SECRET.get()))))));
            return ResponseEntity.status(result.status()).body(meta.data(result.body()));
        }

        @PostMapping(PROTECTED_PATH)
        ResponseEntity<DataResponse<OrderView>> protectedAction(
                @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
                @PathVariable UUID widgetId,
                @RequestBody ProtectedRequest body,
                @AuthenticationPrincipal AuthenticatedSubject subject) {
            IdempotentResult<OrderView> result = runner.run(idempotency.wrap(
                    idempotency.request(subject, "POST " + PROTECTED_PATH, idempotencyKey, Map.of("widgetId", widgetId),
                            body),
                    StoredResponse.of(OrderView.class, 200),
                    new PlaceOrder(subject, widgetId, new OrderRequest(body.reasonText(), 1, Map.of()))));
            return ResponseEntity.status(result.status()).body(meta.data(result.body()));
        }

        @PostMapping(VENUE_PATH)
        ResponseEntity<DataResponse<OrderView>> venueOrder(
                @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
                @PathVariable UUID widgetId,
                @Valid @RequestBody OrderRequest body,
                @AuthenticationPrincipal AuthenticatedSubject subject) {
            return order("POST " + VENUE_PATH, idempotencyKey, widgetId, body, subject);
        }

        /** The shape E7-46 and later write controllers follow. */
        private ResponseEntity<DataResponse<OrderView>> order(String endpoint, UUID key, UUID widgetId,
                                                             OrderRequest body, AuthenticatedSubject subject) {
            IdempotentResult<OrderView> result = runner.run(idempotency.wrap(
                    idempotency.request(subject, endpoint, key, Map.of("widgetId", widgetId), body),
                    StoredResponse.of(OrderView.class, 201, view -> new ResultAggregate("TEST_ORDER", view.orderId())),
                    new PlaceOrder(subject, widgetId, body)));
            return ResponseEntity.status(result.status()).body(meta.data(result.body()));
        }
    }

    /** Counts its runs like {@link PlaceOrder}; answers what {@code result} gives. */
    record Mutation<R>(java.util.function.Supplier<R> result) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public R execute(CommandContext context) {
            EXECUTIONS.incrementAndGet();
            return result.get();
        }
    }

    record PlaceOrder(AuthenticatedSubject subject, UUID widgetId, OrderRequest body) implements Command<OrderView> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public OrderView execute(CommandContext context) {
            EXECUTIONS.incrementAndGet();
            if (FAIL_NEXT.compareAndSet(true, false)) {
                throw new ApiException(ApiErrorCode.SLOT_NO_LONGER_AVAILABLE);
            }
            return new OrderView(UUID.randomUUID(), widgetId, body.label(), body.quantity());
        }
    }

    @BeforeEach
    void setUp() {
        EXECUTIONS.set(0);
        FAIL_NEXT.set(false);
        VISIBLE_UNTIL.set(Instant.MAX);
        customerToken = accessTokens.issue(AuthSubjectType.CUSTOMER, Fixtures.aUser().verified().create().id());
        otherCustomerToken = accessTokens.issue(AuthSubjectType.CUSTOMER, Fixtures.aUser().verified().create().id());
        venueToken = accessTokens.issue(AuthSubjectType.VENUE, Fixtures.aVenueAccount().create().id());
    }

    // ---------------------------------------------------------------------------------------------------- the header

    @Test
    void aMissingKeyIsValidationFailedNamingTheHeaderAndRunsNothing() throws Exception {
        mvc.perform(order(customerToken, CUSTOMER_PATH, UUID.randomUUID(), null, "{\"label\":\"A\",\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value(OpenApiConfig.IDEMPOTENCY_KEY));
        assertThat(EXECUTIONS).hasValue(0);
    }

    @Test
    void aKeyThatIsNotAUuidIsValidationFailed() throws Exception {
        mvc.perform(order(customerToken, CUSTOMER_PATH, UUID.randomUUID(), "not-a-uuid",
                        "{\"label\":\"A\",\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertThat(EXECUTIONS).hasValue(0);
    }

    // ---------------------------------------------------------------------------------------------- replay over HTTP

    @Test
    void theReplayReturnsTheSameStatusAndDataWithItsOwnMeta() throws Exception {
        UUID widget = UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        JsonNode first = body(mvc.perform(order(customerToken, CUSTOMER_PATH, widget, key,
                        "{\"label\":\"A\",\"quantity\":2,\"options\":{\"gift\":{\"wrap\":true,\"note\":\"hi\"}}}"))
                .andExpect(status().isCreated()).andReturn());
        JsonNode replay = body(mvc.perform(order(customerToken, CUSTOMER_PATH, widget, key.toUpperCase(), """
                        {
                          "options" : { "gift" : { "note" : "hi", "wrap" : true } },
                          "quantity" : 2,
                          "label" : "A"
                        }"""))
                .andExpect(status().isCreated()).andReturn());

        assertThat(replay.get("data")).isEqualTo(first.get("data"));
        assertThat(replay.at("/meta/correlationId")).isNotEqualTo(first.at("/meta/correlationId"));
        assertThat(EXECUTIONS).hasValue(1);
    }

    @Test
    void aChangedNestedValueWithTheSameKeyIsIdempotencyKeyReused() throws Exception {
        UUID widget = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        mvc.perform(order(customerToken, CUSTOMER_PATH, widget, key,
                        "{\"label\":\"A\",\"quantity\":2,\"options\":{\"gift\":{\"wrap\":true}}}"))
                .andExpect(status().isCreated());

        mvc.perform(order(customerToken, CUSTOMER_PATH, widget, key,
                        "{\"label\":\"A\",\"quantity\":2,\"options\":{\"gift\":{\"wrap\":false}}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.meta.correlationId").isNotEmpty());
        assertThat(EXECUTIONS).hasValue(1);
    }

    @Test
    void anotherPrincipalWithTheSameKeyGetsItsOwnResultNeverTheStoredOne() throws Exception {
        UUID widget = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        String body = "{\"label\":\"A\",\"quantity\":1}";
        JsonNode first = body(mvc.perform(order(customerToken, CUSTOMER_PATH, widget, key, body))
                .andExpect(status().isCreated()).andReturn());

        JsonNode other = body(mvc.perform(order(otherCustomerToken, CUSTOMER_PATH, widget, key, body))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(order(null, CUSTOMER_PATH, widget, key, body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(order(venueToken, CUSTOMER_PATH, widget, key, body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data").doesNotExist());
        JsonNode venue = body(mvc.perform(order(venueToken, VENUE_PATH, widget, key, body))
                .andExpect(status().isCreated()).andReturn());

        assertThat(other.at("/data/orderId")).isNotEqualTo(first.at("/data/orderId"));
        assertThat(venue.at("/data/orderId")).isNotEqualTo(first.at("/data/orderId"));
        assertThat(EXECUTIONS).hasValue(3);
    }

    @Test
    void aFailedWriteKeepsItsErrorAndTheSameKeyMayBeRetried() throws Exception {
        UUID widget = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        String body = "{\"label\":\"A\",\"quantity\":1}";
        FAIL_NEXT.set(true);

        mvc.perform(order(customerToken, CUSTOMER_PATH, widget, key, body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SLOT_NO_LONGER_AVAILABLE"));
        mvc.perform(order(customerToken, CUSTOMER_PATH, widget, key, body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.widgetId").value(widget.toString()));
        mvc.perform(order(customerToken, CUSTOMER_PATH, widget, key, body)).andExpect(status().isCreated());

        assertThat(EXECUTIONS).hasValue(2);
    }

    @Test
    void bodyValidationStillComesFirst() throws Exception {
        mvc.perform(order(customerToken, CUSTOMER_PATH, UUID.randomUUID(), UUID.randomUUID().toString(),
                        "{\"label\":\" \",\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("label"));
        assertThat(EXECUTIONS).hasValue(0);
    }

    // ------------------------------------------------------------------------------- Owner Password in the request

    @Test
    void theSameOwnerPasswordReplaysEvenWithReorderedMembers() throws Exception {
        UUID widget = UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        JsonNode first = body(mvc.perform(order(venueToken, PROTECTED_PATH, widget, key,
                        "{\"reasonText\":\"x\",\"ownerPassword\":\"4821\",\"options\":{\"a\":1,\"b\":2}}"))
                .andExpect(status().isOk()).andReturn());
        JsonNode replay = body(mvc.perform(order(venueToken, PROTECTED_PATH, widget, key,
                        "{ \"options\" : { \"b\" : 2, \"a\" : 1 }, \"ownerPassword\" : \"4821\", \"reasonText\" : \"x\" }"))
                .andExpect(status().isOk()).andReturn());

        assertThat(replay.get("data")).isEqualTo(first.get("data"));
        assertThat(EXECUTIONS).hasValue(1);
    }

    @Test
    void aChangedMissingOrAddedOwnerPasswordIsIdempotencyKeyReusedNotAReplay() throws Exception {
        UUID widget = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        mvc.perform(order(venueToken, PROTECTED_PATH, widget, key, "{\"reasonText\":\"x\",\"ownerPassword\":\"4821\"}"))
                .andExpect(status().isOk());

        for (String retry : new String[] {
                "{\"reasonText\":\"x\",\"ownerPassword\":\"0000\"}",
                "{\"reasonText\":\"x\",\"ownerPassword\":\"4822\"}",
                "{\"reasonText\":\"x\"}",
                "{\"reasonText\":\"x\",\"ownerPassword\":null}"}) {
            mvc.perform(order(venueToken, PROTECTED_PATH, widget, key, retry))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        String noPassword = UUID.randomUUID().toString();
        mvc.perform(order(venueToken, PROTECTED_PATH, widget, noPassword, "{\"reasonText\":\"x\"}"))
                .andExpect(status().isOk());
        mvc.perform(order(venueToken, PROTECTED_PATH, widget, noPassword,
                        "{\"reasonText\":\"x\",\"ownerPassword\":\"4821\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(EXECUTIONS).hasValue(2);
    }

    @Test
    void noSecretKeyOrRequestValueReachesTheLogs(CapturedOutput output) throws Exception {
        UUID widget = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        String secret = "owner-secret-" + UUID.randomUUID();
        String marker = "request-marker-" + UUID.randomUUID();
        String body = "{\"reasonText\":\"x\",\"ownerPassword\":\"" + secret + "\",\"options\":{\"m\":\"" + marker
                + "\"}}";

        mvc.perform(order(venueToken, PROTECTED_PATH, widget, key, body)).andExpect(status().isOk());
        mvc.perform(order(venueToken, PROTECTED_PATH, widget, key, body)).andExpect(status().isOk());
        mvc.perform(order(venueToken, PROTECTED_PATH, widget, key, body.replace(secret, secret + "x")))
                .andExpect(status().isUnprocessableEntity());

        assertThat(output.getAll()).doesNotContain(secret, marker, "hmac-sha256",
                IntegrationTest.TEST_IDEMPOTENCY_FINGERPRINT_KEY);
    }

    @Test
    void aMissingAndANullOwnerPasswordAreTheSameBoundRequest() throws Exception {
        // ProtectedRequest is a typed body: an absent member and an explicit null bind to the same null, so the command
        // could not tell them apart either. A present value (even "") is a different request.
        UUID widget = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        JsonNode first = body(mvc.perform(order(venueToken, PROTECTED_PATH, widget, key, "{\"reasonText\":\"x\"}"))
                .andExpect(status().isOk()).andReturn());

        JsonNode replay = body(mvc.perform(order(venueToken, PROTECTED_PATH, widget, key,
                        "{\"reasonText\":\"x\",\"ownerPassword\":null}"))
                .andExpect(status().isOk()).andReturn());
        mvc.perform(order(venueToken, PROTECTED_PATH, widget, key, "{\"reasonText\":\"x\",\"ownerPassword\":\"\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(replay.get("data")).isEqualTo(first.get("data"));
        assertThat(EXECUTIONS).hasValue(1);
    }

    // ------------------------------------------------------------------------------------ contact data on a replay

    @Test
    void aReplayAfterTheRetentionBoundaryLeavesThePhoneOutAndRunsNothing() throws Exception {
        UUID widget = UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        JsonNode first = body(mvc.perform(order(customerToken, CONTACT_PATH, widget, key, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.phone").value("+201000000001")).andReturn());
        mvc.perform(order(customerToken, CONTACT_PATH, widget, key, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.phone").value("+201000000001"));

        VISIBLE_UNTIL.set(Instant.now().minusSeconds(1));
        mvc.perform(order(customerToken, CONTACT_PATH, widget, key, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.bookingId").value(first.at("/data/bookingId").asText()))
                .andExpect(jsonPath("$.data.phone").doesNotExist());

        assertThat(EXECUTIONS).as("replays never run the business command").hasValue(1);
    }

    @Test
    void aReplayNeverReachesAnotherOrAnUnauthenticatedCaller() throws Exception {
        UUID widget = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        JsonNode first = body(mvc.perform(order(customerToken, CONTACT_PATH, widget, key, null))
                .andExpect(status().isCreated()).andReturn());

        mvc.perform(order(null, CONTACT_PATH, widget, key, null))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(order(venueToken, CONTACT_PATH, widget, key, null))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.data").doesNotExist());
        JsonNode other = body(mvc.perform(order(otherCustomerToken, CONTACT_PATH, widget, key, null))
                .andExpect(status().isCreated()).andReturn());

        assertThat(other.at("/data/bookingId")).isNotEqualTo(first.at("/data/bookingId"));
        assertThat(EXECUTIONS).hasValue(2);
    }

    // ----------------------------------------------------------------------------- a secret in a dynamic response

    @Test
    void aSecretInADynamicResponseIsAMetaOnly500WithNoRecordAndNoSecretInTheLogs(CapturedOutput output)
            throws Exception {
        String secret = "rt-" + UUID.randomUUID();
        DYNAMIC_SECRET.set(secret);
        String key = UUID.randomUUID().toString();

        String response = mvc.perform(order(customerToken, LOOSE_PATH, UUID.randomUUID(), key, null))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.meta.correlationId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(secret, "refreshToken");
        assertThat(output.getAll()).as("the defect is logged by path, never by value")
                .contains("/data/details/session/refreshToken").doesNotContain(secret);
        assertThat(JdbcClient.create(appDataSource)
                .sql("SELECT count(*) FROM technical.idempotency_record WHERE idempotency_key = :key")
                .param("key", key).query(Long.class).single()).isZero();
    }

    // ------------------------------------------------------------------------- the real ADR-015 operations (stubs)

    @Test
    void theRealBookingWritesRequireTheKeyAndTheUnimplementedOnesStayStubs() throws Exception {
        String create = "{\"resourceId\":\"" + UUID.randomUUID() + "\",\"startAt\":\"2026-10-12T18:00:00Z\","
                + "\"durationMinutes\":120}";
        UUID booking = UUID.randomUUID();

        mvc.perform(write(customerToken, "/api/v1/customer/bookings", null, create))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value(OpenApiConfig.IDEMPOTENCY_KEY));
        // E7-46: creation is real; with the key its unknown resourceId reaches the route and is 404 NOT_FOUND.
        mvc.perform(write(customerToken, "/api/v1/customer/bookings", UUID.randomUUID().toString(), create))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(write(customerToken, "/api/v1/customer/bookings/" + booking + "/cancel", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value(OpenApiConfig.IDEMPOTENCY_KEY));
        mvc.perform(write(venueToken, "/api/v1/venue/bookings/" + booking + "/accept", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value(OpenApiConfig.IDEMPOTENCY_KEY));
        mvc.perform(write(venueToken, "/api/v1/venue/bookings/" + booking + "/accept", UUID.randomUUID().toString(),
                        null))
                .andExpect(status().isNotImplemented());
    }

    @Test
    void anOptionalKeyWriteDoesNotRequireIt() throws Exception {
        mvc.perform(write(customerToken, "/api/v1/customer/notifications/" + UUID.randomUUID() + "/read", null, null))
                .andExpect(status().isNotImplemented());
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private MockHttpServletRequestBuilder order(String token, String path, UUID widget, String key, String body) {
        return write(token, path.replace("{widgetId}", widget.toString()), key, body);
    }

    private static MockHttpServletRequestBuilder write(String token, String path, String key, String body) {
        MockHttpServletRequestBuilder request = post(path);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            request.header(OpenApiConfig.IDEMPOTENCY_KEY, key);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
