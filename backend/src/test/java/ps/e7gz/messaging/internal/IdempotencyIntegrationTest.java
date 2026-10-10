package ps.e7gz.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.error.DatabaseErrorCodes;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.concurrency.ConcurrentRace;
import ps.e7gz.messaging.api.CommandAnswer;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.NewOutboxEvent;
import ps.e7gz.messaging.api.ReplayProjection;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.messaging.api.ResultAggregate;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-42 done-when (ADR-015, arch 08 section 6), against the real {@code technical.idempotency_begin} /
 * {@code idempotency_complete} through {@code CommandRunner}: replay and conflict, principal and endpoint scoping, one
 * business execution under sequential and concurrent duplicates, rollback leaving no record, the record living in the
 * business transaction, and nothing secret stored.
 *
 * <p>The business mutation is an outbox event of a type unique to the test (a real row in the command's transaction);
 * {@code executions} counts how often the command body ran.
 */
class IdempotencyIntegrationTest extends IntegrationTest {

    static final String ENDPOINT = "POST /api/v1/customer/test-e7-42/widgets/{widgetId}/orders";

    @Autowired
    CommandRunner runner;

    @Autowired
    Idempotency idempotency;

    @Autowired
    Outbox outbox;

    @Autowired
    JdbcIdempotencyStore store;

    @Autowired
    DataSource appDataSource;

    @Autowired
    ObjectMapper json;

    JdbcClient db;
    String eventType;
    AtomicInteger executions;
    CorrelationId.Scope correlation;
    UUID correlationId;

    /** The request body of the test operation. {@code ownerPassword} stands for any secret a request may carry. */
    public record OrderRequest(String label, int quantity, Map<String, Object> options, String ownerPassword) {
    }

    /** The response data of the test operation. */
    public record OrderView(UUID orderId, UUID widgetId, String label, int quantity) {
    }

    /** A response that carries a secret: the database must refuse to store it. */
    public record SessionView(UUID sessionId, String accessToken) {
    }

    /** A response whose untyped part may carry anything, a secret included. */
    public record LooseView(UUID id, Map<String, Object> details) {
    }

    /** Untyped list elements. */
    public record ListView(UUID id, List<Object> items) {
    }

    /** A field declared as {@code Object}, here holding a bean with a map. */
    public record ObjectView(UUID id, Object details) {
    }

    public record Inner(Map<String, Object> extra) {
    }

    /** A response with counterpart contact data (arch 10 section 5.1). */
    public record ContactView(UUID bookingId, @JsonInclude(JsonInclude.Include.NON_NULL) String phone) {
    }

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(appDataSource);
        eventType = "TestE742_" + UUID.randomUUID().toString().replace("-", "");
        executions = new AtomicInteger();
        correlationId = CorrelationId.generate();
        correlation = CorrelationId.open(correlationId);
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    // ------------------------------------------------------------------------------------------ replay and conflict

    @Test
    void sameKeyAndSameRequestReplaysTheStoredResultAndRunsTheCommandOnce() {
        AuthenticatedSubject customer = customer();
        UUID widget = UUID.randomUUID();
        UUID key = UUID.randomUUID();

        IdempotentResult<OrderView> first = place(customer, ENDPOINT, key, widget, order("A", 2));
        IdempotentResult<OrderView> second = place(customer, ENDPOINT, key, widget, order("A", 2));
        IdempotentResult<OrderView> third = place(customer, ENDPOINT, key, widget, order("A", 2));

        assertThat(first.replayed()).isFalse();
        assertThat(first.status()).isEqualTo(201);
        assertThat(List.of(second, third)).allSatisfy(replay -> {
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.status()).isEqualTo(201);
            assertThat(replay.body()).isEqualTo(first.body());
        });
        assertThat(executions).hasValue(1);
        assertThat(events()).isEqualTo(1);

        Map<String, Object> row = record(customer, ENDPOINT, key);
        assertThat(row.get("state")).isEqualTo("COMPLETED");
        assertThat(((Number) row.get("response_status")).intValue()).isEqualTo(201);
        assertThat(row.get("result_aggregate_type")).isEqualTo("TEST_ORDER");
        assertThat(row.get("result_aggregate_id")).isEqualTo(first.body().orderId());
        assertThat(row.get("correlation_id")).isEqualTo(correlationId);
        assertThat(row.get("principal_type")).isEqualTo("CUSTOMER");
        assertThat(row.get("principal_id")).isEqualTo(customer.id());
        assertThat(row.get("idempotency_key")).isEqualTo(key.toString());
        assertThat(Duration.between(instant(row.get("created_at")), instant(row.get("expires_at"))))
                .isBetween(Duration.ofDays(30).minusSeconds(5), Duration.ofDays(30));
    }

    @Test
    void sameKeyWithADifferentRequestIsIdempotencyKeyReusedAndChangesNothing() {
        AuthenticatedSubject customer = customer();
        UUID widget = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        place(customer, ENDPOINT, key, widget, order("A", 2));
        Map<String, Object> before = record(customer, ENDPOINT, key);

        assertKeyReused(() -> place(customer, ENDPOINT, key, widget, order("A", 3)));
        assertKeyReused(() -> place(customer, ENDPOINT, key, UUID.randomUUID(), order("A", 2)));
        assertKeyReused(() -> place(customer, ENDPOINT, key, widget,
                new OrderRequest("A", 2, Map.of("gift", Map.of("wrap", false)), null)));

        assertThat(executions).hasValue(1);
        assertThat(events()).isEqualTo(1);
        assertThat(record(customer, ENDPOINT, key)).isEqualTo(before);
    }

    @Test
    void aChangedNestedValueConflictsAndAnEqualNestedValueReplays() {
        AuthenticatedSubject customer = customer();
        UUID widget = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        Map<String, Object> options = Map.of("gift", Map.of("wrap", true, "note", "hi"), "colours", List.of("red", "blue"));
        place(customer, ENDPOINT, key, widget, new OrderRequest("A", 2, options, null));

        IdempotentResult<OrderView> replay = place(customer, ENDPOINT, key, widget, new OrderRequest("A", 2,
                Map.of("colours", List.of("red", "blue"), "gift", Map.of("note", "hi", "wrap", true)), null));
        assertThat(replay.replayed()).isTrue();

        assertKeyReused(() -> place(customer, ENDPOINT, key, widget, new OrderRequest("A", 2,
                Map.of("gift", Map.of("wrap", true, "note", "hi!"), "colours", List.of("red", "blue")), null)));
        assertKeyReused(() -> place(customer, ENDPOINT, key, widget, new OrderRequest("A", 2,
                Map.of("gift", Map.of("wrap", true, "note", "hi"), "colours", List.of("blue", "red")), null)));
        assertThat(executions).hasValue(1);
    }

    // ------------------------------------------------------------------------------------- principals and endpoints

    @Test
    void differentPrincipalsUseTheSameKeyIndependently() {
        AuthenticatedSubject first = customer();
        AuthenticatedSubject second = customer();
        UUID widget = UUID.randomUUID();
        UUID key = UUID.randomUUID();

        IdempotentResult<OrderView> a = place(first, ENDPOINT, key, widget, order("A", 1));
        IdempotentResult<OrderView> b = place(second, ENDPOINT, key, widget, order("A", 1));

        assertThat(a.replayed()).isFalse();
        assertThat(b.replayed()).as("another principal never gets the first one's stored response").isFalse();
        assertThat(b.body().orderId()).isNotEqualTo(a.body().orderId());
        assertThat(executions).hasValue(2);
        // Each principal's record is its own: the second one's key now conflicts only with its own request.
        assertKeyReused(() -> place(second, ENDPOINT, key, widget, order("B", 1)));
        assertThat(place(first, ENDPOINT, key, widget, order("A", 1)).body()).isEqualTo(a.body());
    }

    @Test
    void customerVenueAndAdminPrincipalsWithTheSameIdAreDifferentPrincipals() {
        UUID shared = Fixtures.aUser().verified().create().id();
        UUID venue = Fixtures.aVenue().create().id();
        JdbcClient owner = ownerDb();
        owner.sql("""
                        INSERT INTO iam.venue_account (id, venue_id, login_identifier, password_hash, account_status,
                                                       language_preference)
                        VALUES (:id, :venue, :login, 'x', 'ENABLED', 'en')""")
                .param("id", shared).param("venue", venue).param("login", "e742-" + shared).update();
        UUID key = UUID.randomUUID();
        UUID widget = UUID.randomUUID();

        IdempotentResult<OrderView> asCustomer = place(new AuthenticatedSubject(AuthSubjectType.CUSTOMER, shared),
                ENDPOINT, key, widget, order("A", 1));
        IdempotentResult<OrderView> asVenue = place(new AuthenticatedSubject(AuthSubjectType.VENUE, shared),
                "POST /api/v1/venue/test-e7-42/widgets/{widgetId}/orders", key, widget, order("A", 1));

        assertThat(asCustomer.replayed()).isFalse();
        assertThat(asVenue.replayed()).isFalse();
        assertThat(executions).hasValue(2);
        assertThat(db.sql("""
                        SELECT principal_type FROM technical.idempotency_record
                         WHERE principal_id = :id AND idempotency_key = :key ORDER BY principal_type""")
                .param("id", shared).param("key", key.toString()).query(String.class).list())
                .containsExactly("CUSTOMER", "VENUE");
    }

    @Test
    void differentEndpointsWithTheSameKeyDoNotReplayEachOther() {
        AuthenticatedSubject customer = customer();
        UUID widget = UUID.randomUUID();
        UUID key = UUID.randomUUID();

        IdempotentResult<OrderView> orders = place(customer, ENDPOINT, key, widget, order("A", 1));
        IdempotentResult<OrderView> returns = place(customer,
                "POST /api/v1/customer/test-e7-42/widgets/{widgetId}/returns", key, widget, order("A", 1));

        assertThat(orders.replayed()).isFalse();
        assertThat(returns.replayed()).isFalse();
        assertThat(returns.body().orderId()).isNotEqualTo(orders.body().orderId());
        assertThat(executions).hasValue(2);
        assertThat(events()).isEqualTo(2);
    }

    @Test
    void anAdminPrincipalWorksOnTheAdminConnection() {
        UUID admin = Fixtures.anAdmin().create().id();
        AuthenticatedSubject subject = new AuthenticatedSubject(AuthSubjectType.ADMIN, admin);
        String endpoint = "POST /api/v1/admin/test-e7-42/widgets/{widgetId}/orders";
        UUID widget = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        IdempotentRequest request = idempotency.request(subject, endpoint, key, Map.of("widgetId", widget),
                order("A", 1));

        Function<IdempotentRequest, IdempotentResult<OrderView>> run = r -> runner.run(idempotency.wrap(r,
                StoredResponse.of(OrderView.class, 200), command(
                        CommandScope.admin(admin, correlationId, LockSet.empty()),
                        ctx -> {
                            executions.incrementAndGet();
                            return new OrderView(UUID.randomUUID(), widget, "A", 1);
                        })));

        IdempotentResult<OrderView> first = run.apply(request);
        IdempotentResult<OrderView> replay = run.apply(request);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(executions).hasValue(1);
    }

    // ------------------------------------------------------------------------------------------------ concurrency

    @Test
    void concurrentDuplicatesProduceOneBusinessMutationAndTheSameResult() {
        AuthenticatedSubject customer = customer();
        UUID widget = UUID.randomUUID();
        IdempotentRequest request = idempotency.request(customer, ENDPOINT, UUID.randomUUID(),
                Map.of("widgetId", widget), withSecret("4821"));

        ConcurrentRace.Result<IdempotentResult<OrderView>> race = ConcurrentRace.run(5, Duration.ofSeconds(30),
                (racer, start) -> {
                    try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
                        Command<IdempotentResult<OrderView>> idempotent = wrap(request, widget);
                        // Every racer is inside its own open command transaction before any of them begins the record.
                        return runner.run(command(idempotent.scope(), ctx -> {
                            start.await();
                            return idempotent.execute(ctx);
                        }));
                    }
                });

        assertThat(race.failures()).isEmpty();
        assertThat(race.values()).hasSize(5);
        assertThat(race.values().stream().filter(r -> !r.replayed())).hasSize(1);
        assertThat(race.values()).extracting(IdempotentResult::body).containsOnly(race.values().get(0).body());
        assertThat(executions).hasValue(1);
        assertThat(events()).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------- one transaction, rollback

    @Test
    void aFailedCommandLeavesNoRecordAndARetryWithTheSameKeyRunsNormally() {
        AuthenticatedSubject customer = customer();
        UUID widget = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        IdempotentRequest request = idempotency.request(customer, ENDPOINT, key, Map.of("widgetId", widget),
                order("A", 1));

        assertThatThrownBy(() -> runner.run(idempotency.wrap(request, response(), command(lockUser(customer), ctx -> {
            executions.incrementAndGet();
            publish(ctx, widget);
            throw new ApiException(ApiErrorCode.SLOT_NO_LONGER_AVAILABLE);
        })))).isInstanceOf(ApiException.class);

        assertThat(recordCount(customer, ENDPOINT, key)).isZero();
        assertThat(events()).isZero();

        IdempotentResult<OrderView> retry = runner.run(wrap(request, widget));
        assertThat(retry.replayed()).isFalse();
        assertThat(executions).hasValue(2);
        assertThat(events()).isEqualTo(1);
        assertThat(record(customer, ENDPOINT, key).get("state")).isEqualTo("COMPLETED");
    }

    @Test
    void beginTheCommandAndCompleteShareOneUncommittedTransaction() {
        AuthenticatedSubject customer = customer();
        UUID widget = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        IdempotentRequest request = idempotency.request(customer, ENDPOINT, key, Map.of("widgetId", widget),
                order("A", 1));
        AtomicReference<String> insideState = new AtomicReference<>();
        AtomicReference<Long> outsideCount = new AtomicReference<>();

        runner.run(idempotency.wrap(request, response(), command(lockUser(customer), ctx -> {
            insideState.set(ctx.jdbc().sql("""
                            SELECT state FROM technical.idempotency_record
                             WHERE principal_type = 'CUSTOMER' AND principal_id = :id AND idempotency_key = :key""")
                    .param("id", customer.id()).param("key", key.toString()).query(String.class).single());
            // A connection of its own: a JdbcClient on the app DataSource would join the runner's transaction.
            outsideCount.set(ownerDb()
                    .sql("SELECT count(*) FROM technical.idempotency_record WHERE idempotency_key = :key")
                    .param("key", key.toString()).query(Long.class).single());
            return new OrderView(UUID.randomUUID(), widget, "A", 1);
        })));

        assertThat(insideState.get()).as("begun in the command's transaction").isEqualTo("IN_PROGRESS");
        assertThat(outsideCount.get()).as("invisible to other transactions until commit").isZero();
        assertThat(record(customer, ENDPOINT, key).get("state")).isEqualTo("COMPLETED");
    }

    @Test
    void a40001RetryRerunsBeginTheCommandAndCompleteInTheNewTransaction() {
        AuthenticatedSubject customer = customer();
        UUID widget = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        IdempotentRequest request = idempotency.request(customer, ENDPOINT, key, Map.of("widgetId", widget),
                order("A", 1));

        IdempotentResult<OrderView> result = runner.run(idempotency.wrap(request, response(),
                command(lockUser(customer), ctx -> {
                    executions.incrementAndGet();
                    publish(ctx, widget);
                    if (ctx.attempt() == 1) {
                        ctx.jdbc().sql("DO $$ BEGIN RAISE EXCEPTION USING ERRCODE = '40001', MESSAGE = 'test'; END $$")
                                .update();
                    }
                    return new OrderView(UUID.randomUUID(), widget, "A", 1);
                })));

        assertThat(result.replayed()).isFalse();
        assertThat(executions).hasValue(2);
        assertThat(events()).as("the first attempt rolled back with its record").isEqualTo(1);
        assertThat(recordCount(customer, ENDPOINT, key)).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------------- what is stored

    // ------------------------------------------------------------------------------------- secrets in the request

    @Test
    void theSameSecretReplaysAndAChangedMissingOrAddedSecretIsIdempotencyKeyReused() {
        AuthenticatedSubject customer = customer();
        UUID widget = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        IdempotentResult<OrderView> first = place(customer, ENDPOINT, key, widget, withSecret("4821"));

        IdempotentResult<OrderView> replay = place(customer, ENDPOINT, key, widget,
                new OrderRequest("A", 1, Map.of(), "4821"));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body()).isEqualTo(first.body());

        assertKeyReused(() -> place(customer, ENDPOINT, key, widget, withSecret("0000")));   // changed
        assertKeyReused(() -> place(customer, ENDPOINT, key, widget, withSecret("4822")));   // one digit
        assertKeyReused(() -> place(customer, ENDPOINT, key, widget, withSecret(null)));     // missing
        assertThat(executions).hasValue(1);
        assertThat(events()).isEqualTo(1);

        UUID otherKey = UUID.randomUUID();
        place(customer, ENDPOINT, otherKey, widget, withSecret(null));
        assertKeyReused(() -> place(customer, ENDPOINT, otherKey, widget, withSecret("4821")));  // added
        assertThat(executions).hasValue(2);
    }

    @Test
    void neitherASecretNorTheKeyNorTheRequestIsStoredOnlyTheResponseData() throws Exception {
        AuthenticatedSubject customer = customer();
        UUID widget = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        String marker = "note-" + UUID.randomUUID();
        OrderRequest request = new OrderRequest("A", 1, Map.of("note", marker), "4821");

        IdempotentResult<OrderView> first = place(customer, ENDPOINT, key, widget, request);

        Map<String, Object> row = record(customer, ENDPOINT, key);
        JsonNode stored = json.readTree(row.get("response_body").toString());
        assertThat(stored).isEqualTo(json.createObjectNode().set("data", json.valueToTree(first.body())));
        assertThat(row.toString()).doesNotContain("4821", "ownerPassword", "hmac", marker, "meta", "serverTime",
                IntegrationTest.TEST_IDEMPOTENCY_FINGERPRINT_KEY);
        // No unkeyed digest of the secret either: what a plain SHA-256 fingerprint would have exposed to a dictionary.
        assertThat(row.toString()).doesNotContain(sha256("4821"), sha256("\"4821\""),
                HexFormat.of().formatHex(Base64.getDecoder().decode(IntegrationTest.TEST_IDEMPOTENCY_FINGERPRINT_KEY)));
        assertThat(row.get("request_fingerprint").toString()).matches("^[0-9a-f]{64}$")
                .isEqualTo(idempotency.request(customer, ENDPOINT, key, Map.of("widgetId", widget), request)
                        .fingerprint());
    }

    // ------------------------------------------------------------------------------------- secrets in the response

    @Test
    void aResponseTypeWithASecretFieldIsRefusedBeforeAnythingRuns() {
        AuthenticatedSubject customer = customer();
        UUID widget = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        IdempotentRequest request = idempotency.request(customer, ENDPOINT, key, Map.of("widgetId", widget),
                order("A", 1));

        assertThatThrownBy(() -> idempotency.wrap(request, StoredResponse.of(SessionView.class, 201),
                command(lockUser(customer), ctx -> {
                    executions.incrementAndGet();
                    return new SessionView(UUID.randomUUID(), "eyJ.secret.jwt");
                })))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/accessToken").hasMessageNotContaining("eyJ");

        assertThat(executions).hasValue(0);
        assertThat(recordCount(customer, ENDPOINT, key)).isZero();
    }

    @Test
    void aSecretInAnUntypedResponseFieldRollsTheCommandBackWithAClearDefect() {
        String secret = "rt-secret-" + UUID.randomUUID();
        assertDynamicSecretRefused(LooseView.class,
                () -> new LooseView(UUID.randomUUID(), Map.of("session", Map.of("refreshToken", secret))),
                "/data/details/session/refreshToken", secret);
    }

    @Test
    void aSecretDeepInsideListsOfMapsIsRefused() {
        String secret = "pin-" + UUID.randomUUID();
        assertDynamicSecretRefused(ListView.class,
                () -> new ListView(UUID.randomUUID(), List.of("x", Map.of("rows", List.of(Map.of("ok", 1),
                        Map.of("pin", Map.of("value", secret)))))),
                "/data/items[1]/rows[1]/pin", secret);
    }

    @Test
    void aSecretBehindAnObjectFieldIsRefused() {
        String secret = "otp-" + UUID.randomUUID();
        assertDynamicSecretRefused(ObjectView.class,
                () -> new ObjectView(UUID.randomUUID(), new Inner(Map.of("otpCode", secret))),
                "/data/details/extra/otpCode", secret);
    }

    /**
     * The command writes real business state (an outbox event and an {@code iam.user_account} row) and returns a
     * response whose secret only exists at run time: refused before {@code idempotency_complete}, with the path and not
     * the value, and everything rolls back.
     */
    private <R> void assertDynamicSecretRefused(Class<R> type, java.util.function.Supplier<R> response, String path,
                                                String secret) {
        AuthenticatedSubject customer = customer();
        UUID widget = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        IdempotentRequest request = idempotency.request(customer, ENDPOINT, key, Map.of("widgetId", widget),
                order("A", 1));
        AtomicReference<UUID> createdUser = new AtomicReference<>();

        Command<IdempotentResult<R>> write = idempotency.wrap(request, StoredResponse.of(type, 201),
                command(lockUser(customer), ctx -> {
                    publish(ctx, widget);
                    createdUser.set(Fixtures.aUser().create(ctx.jdbc()).id());
                    return response.get();
                }));
        assertThatThrownBy(() -> runner.run(write))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(path).hasMessageNotContaining(secret);

        assertThat(recordCount(customer, ENDPOINT, key)).as("no idempotency record").isZero();
        assertThat(events()).as("no outbox event").isZero();
        assertThat(ownerDb().sql("SELECT count(*) FROM iam.user_account WHERE id = :id")
                .param("id", createdUser.get()).query(Long.class).single()).as("no business row").isZero();
    }

    @Test
    void theDatabaseStillRefusesASecretInAStoredResponse() {
        AuthenticatedSubject customer = customer();
        IdempotentRequest request = idempotency.request(customer, ENDPOINT, UUID.randomUUID(),
                Map.of("widgetId", UUID.randomUUID()), order("A", 1));

        assertThatThrownBy(() -> runner.run(command(lockUser(customer), ctx -> {
            store.begin(ctx.jdbc(), request, ctx.commandNow().plus(Duration.ofDays(30)), correlationId);
            store.complete(ctx.jdbc(), request, 201, "{\"data\":{\"pin\":\"4821\"}}", null);
            return null;
        }))).isInstanceOf(DataAccessException.class).hasMessageContaining("forbidden secret field: /data/pin");
    }

    // ------------------------------------------------------------------------------------ contact data on a replay

    @Test
    void aReplayReAppliesTheCallersReplayProjectionWithoutChangingTheStoredResponse() throws Exception {
        AuthenticatedSubject customer = customer();
        UUID widget = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        IdempotentRequest request = idempotency.request(customer, ENDPOINT, key, Map.of("widgetId", widget),
                order("A", 1));
        // A concrete retention rule in the shape of arch 10 section 5.1: the phone is visible while the command instant
        // is before the boundary (e.g. terminalAt + 2h). The boundary stands for the booking's current state, which the
        // real resolver (E7-46/E7-49) reads from the database; it moves when the booking becomes terminal.
        AtomicReference<Instant> visibleUntil = new AtomicReference<>(Instant.MAX);
        StoredResponse<ContactView> response = StoredResponse.of(ContactView.class, 201)
                .replayedThrough(new VisibilityProjection(visibleUntil));
        Command<IdempotentResult<ContactView>> write = idempotency.wrap(request, response,
                command(lockUser(customer), ctx -> {
                    executions.incrementAndGet();
                    publish(ctx, widget);
                    return new ContactView(UUID.randomUUID(), "+201000000001");
                }));

        IdempotentResult<ContactView> first = runner.run(write);
        IdempotentResult<ContactView> beforeBoundary = runner.run(write);
        visibleUntil.set(Instant.now().minusSeconds(1));   // the retention boundary has passed
        IdempotentResult<ContactView> afterBoundary = runner.run(write);

        assertThat(first.body().phone()).isEqualTo("+201000000001");
        assertThat(beforeBoundary.replayed()).isTrue();
        assertThat(beforeBoundary.body()).isEqualTo(first.body());
        assertThat(afterBoundary.replayed()).isTrue();
        assertThat(afterBoundary.body()).isEqualTo(new ContactView(first.body().bookingId(), null));
        assertThat(json.valueToTree(afterBoundary.body()).has("phone")).as("absent, not null").isFalse();
        assertThat(executions).as("a replay never runs the business command").hasValue(1);
        assertThat(events()).as("nor writes its business change").isEqualTo(1);
        assertThat(record(customer, ENDPOINT, key).get("response_body").toString()).contains("+201000000001");
    }

    /** The test's stand-in for a contact visibility resolver: hides the phone from the boundary on. */
    record VisibilityProjection(AtomicReference<Instant> visibleUntil) implements ReplayProjection<ContactView> {

        @Override
        public ContactView onReplay(CommandContext context, ContactView stored) {
            return context.commandNow().isBefore(visibleUntil.get()) ? stored : new ContactView(stored.bookingId(), null);
        }
    }

    @Test
    void aProjectionThatAddsOrChangesDataIsADefectAndShowsNothing() {
        AuthenticatedSubject customer = customer();
        UUID widget = UUID.randomUUID();
        IdempotentRequest request = idempotency.request(customer, ENDPOINT, UUID.randomUUID(),
                Map.of("widgetId", widget), order("A", 1));
        UUID booking = UUID.randomUUID();
        AtomicReference<ReplayProjection<ContactView>> projection = new AtomicReference<>((ctx, stored) -> stored);
        Command<IdempotentResult<ContactView>> write = idempotency.wrap(request,
                StoredResponse.of(ContactView.class, 201).replayedThrough((ctx, s) -> projection.get().onReplay(ctx, s)),
                command(lockUser(customer), ctx -> new ContactView(booking, null)));
        runner.run(write);

        projection.set((ctx, stored) -> new ContactView(stored.bookingId(), "+201000000009"));   // adds a phone
        assertThatThrownBy(() -> runner.run(write)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/data/phone").hasMessageNotContaining("+201000000009");
        projection.set((ctx, stored) -> new ContactView(UUID.randomUUID(), null));                 // changes the id
        assertThatThrownBy(() -> runner.run(write)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/data/bookingId");
        projection.set((ctx, stored) -> new ContactView(null, null));                               // nulls the id
        assertThatThrownBy(() -> runner.run(write)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/data/bookingId");
        projection.set((ctx, stored) -> null);
        assertThatThrownBy(() -> runner.run(write)).isInstanceOf(IllegalStateException.class);
        projection.set((ctx, stored) -> stored);                                                    // unchanged
        assertThat(runner.run(write).body()).isEqualTo(new ContactView(booking, null));
    }

    @Test
    void notOnlyRemovedAcceptsLeftOutMembersOnly() {
        JsonNode stored = json.valueToTree(Map.of("a", 1, "list", List.of(Map.of("x", 1, "y", 2)), "phone", "+20"));

        assertThat(StoredResponseTypes.notOnlyRemoved(stored, stored, "")).isEmpty();
        assertThat(StoredResponseTypes.notOnlyRemoved(stored,
                json.valueToTree(Map.of("a", 1, "list", List.of(Map.of("x", 1)))), "")).isEmpty();
        assertThat(StoredResponseTypes.notOnlyRemoved(stored,
                json.valueToTree(Map.of("a", 2, "list", List.of(Map.of("x", 1)))), "")).contains("/a");
        assertThat(StoredResponseTypes.notOnlyRemoved(stored,
                json.valueToTree(Map.of("a", 1, "list", List.of())), "")).contains("/list");
        assertThat(StoredResponseTypes.notOnlyRemoved(stored,
                json.valueToTree(Map.of("a", 1, "extra", true)), "")).contains("/extra");
    }

    @Test
    void aResponseWithAPhoneAndNoReplayProjectionIsRefused() {
        IdempotentRequest request = idempotency.request(customer(), ENDPOINT, UUID.randomUUID(),
                Map.of("widgetId", UUID.randomUUID()), order("A", 1));

        assertThatThrownBy(() -> idempotency.wrap(request, StoredResponse.of(ContactView.class, 201),
                command(CommandScope.shared(LockSet.empty()), ctx -> new ContactView(UUID.randomUUID(), null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("/phone");
    }

    @Test
    void aCommandWithoutResponseDataReplaysItsStatusWithNoBody() {
        AuthenticatedSubject customer = customer();
        UUID widget = UUID.randomUUID();
        IdempotentRequest request = idempotency.request(customer, ENDPOINT, UUID.randomUUID(),
                Map.of("widgetId", widget), null);
        Command<IdempotentResult<OrderView>> noContent = idempotency.wrap(request, StoredResponse.of(OrderView.class, 204),
                command(lockUser(customer), ctx -> {
                    executions.incrementAndGet();
                    return null;
                }));

        IdempotentResult<OrderView> first = runner.run(noContent);
        IdempotentResult<OrderView> replay = runner.run(noContent);

        assertThat(first).isEqualTo(new IdempotentResult<OrderView>(204, null, false));
        assertThat(replay).isEqualTo(new IdempotentResult<OrderView>(204, null, true));
        assertThat(executions).hasValue(1);
    }

    // -------------------------------------------------------------- committed refusal (E7-47, PO decision D2)

    @Test
    void aCommittedRefusalKeepsItsWritesAndStoresItsOwnStatusAndErrorBody() {
        AuthenticatedSubject customer = customer();
        UUID widget = UUID.randomUUID();
        UUID key = UUID.randomUUID();

        IdempotentResult<OrderView> first = refuse(customer, key, widget, order("A", 1));

        assertThat(first.status()).isEqualTo(422);
        assertThat(first.body()).isNull();
        assertThat(first.replayed()).isFalse();
        assertThat(first.refusal().code()).isEqualTo(ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE);
        assertThat(first.refusal().details()).containsEntry("reason", "TEST");
        assertThat(events()).as("the command's write committed").isEqualTo(1);
        Map<String, Object> row = record(customer, ENDPOINT, key);
        assertThat(row.get("state")).isEqualTo("COMPLETED");
        assertThat(((Number) row.get("response_status")).intValue()).as("never a success status").isEqualTo(422);
        assertThat(row.get("response_body").toString())
                .isEqualTo("{\"error\": {\"code\": \"VENUE_SELLING_BLOCK_ACTIVE\", \"details\": {\"reason\": \"TEST\"}}}");
        assertThat(row.get("result_aggregate_type")).isNull();
        assertThatThrownBy(first::throwIfRefused).isSameAs(first.refusal());
    }

    @Test
    void aReplayOfACommittedRefusalAnswersTheSameRefusalWithoutRunningTheCommand() {
        AuthenticatedSubject customer = customer();
        UUID widget = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        IdempotentResult<OrderView> first = refuse(customer, key, widget, order("A", 1));

        IdempotentResult<OrderView> replay = refuse(customer, key, widget, order("A", 1));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status()).isEqualTo(first.status());
        assertThat(replay.body()).isNull();
        assertThat(replay.refusal().code()).isEqualTo(first.refusal().code());
        assertThat(replay.refusal().field()).isEqualTo(first.refusal().field());
        assertThat(replay.refusal().details()).isEqualTo(first.refusal().details());
        assertThat(executions).hasValue(1);
        assertThat(events()).isEqualTo(1);
        assertKeyReused(() -> refuse(customer, key, widget, order("B", 1)));
    }

    @Test
    void aThrownRefusalStillRollsEverythingBack() {
        AuthenticatedSubject customer = customer();
        UUID widget = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        IdempotentRequest request = idempotency.request(customer, ENDPOINT, key, Map.of("widgetId", widget),
                order("A", 1));

        assertThatThrownBy(() -> runner.run(idempotency.wrapAnswer(request, response(),
                command(CommandScope.shared(LockSet.empty()), ctx -> {
                    publish(ctx, UUID.randomUUID());
                    throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
                })))).isInstanceOf(ApiException.class);

        assertThat(events()).isZero();
        assertThat(recordCount(customer, ENDPOINT, key)).isZero();
    }

    @Test
    void anOkAnswerIsStoredAsASuccessExactlyLikeWrap() {
        AuthenticatedSubject customer = customer();
        UUID widget = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        IdempotentRequest request = idempotency.request(customer, ENDPOINT, key, Map.of("widgetId", widget),
                order("A", 1));
        Command<IdempotentResult<OrderView>> ok = idempotency.wrapAnswer(request, response(),
                command(CommandScope.shared(LockSet.empty()), ctx -> {
                    executions.incrementAndGet();
                    return CommandAnswer.ok(new OrderView(UUID.randomUUID(), widget, "A", 1));
                }));

        IdempotentResult<OrderView> first = runner.run(ok);
        IdempotentResult<OrderView> replay = runner.run(ok);

        assertThat(first.status()).isEqualTo(201);
        assertThat(first.refusal()).isNull();
        assertThat(first.throwIfRefused()).isSameAs(first);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.refusal()).isNull();
        assertThat(executions).hasValue(1);
    }

    private IdempotentResult<OrderView> refuse(AuthenticatedSubject principal, UUID key, UUID widget,
                                               OrderRequest body) {
        IdempotentRequest request = idempotency.request(principal, ENDPOINT, key, Map.of("widgetId", widget), body);
        return runner.run(idempotency.wrapAnswer(request, response(),
                command(CommandScope.shared(LockSet.empty()), ctx -> {
                    executions.incrementAndGet();
                    publish(ctx, UUID.randomUUID());
                    return CommandAnswer.refused(new ApiException(ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE, null,
                            Map.of("reason", "TEST")));
                })));
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private IdempotentResult<OrderView> place(AuthenticatedSubject principal, String endpoint, UUID key, UUID widget,
                                              OrderRequest body) {
        IdempotentRequest request = idempotency.request(principal, endpoint, key, Map.of("widgetId", widget), body);
        return runner.run(wrap(request, widget));
    }

    /** The test operation: one outbox row per run, answering a new order. */
    private Command<IdempotentResult<OrderView>> wrap(IdempotentRequest request, UUID widget) {
        return idempotency.wrap(request, response(), command(CommandScope.shared(LockSet.empty()), ctx -> {
            executions.incrementAndGet();
            UUID order = UUID.randomUUID();
            publish(ctx, order);
            return new OrderView(order, widget, "A", 1);
        }));
    }

    private static StoredResponse<OrderView> response() {
        return StoredResponse.of(OrderView.class, 201, view -> new ResultAggregate("TEST_ORDER", view.orderId()));
    }

    private void publish(CommandContext ctx, UUID aggregate) {
        outbox.publish(ctx, new NewOutboxEvent(eventType, "TEST", aggregate, Map.of("aggregate", aggregate)));
    }

    private static OrderRequest order(String label, int quantity) {
        return new OrderRequest(label, quantity, Map.of(), null);
    }

    private static OrderRequest withSecret(String ownerPassword) {
        return new OrderRequest("A", 1, Map.of(), ownerPassword);
    }

    private static CommandScope lockUser(AuthenticatedSubject customer) {
        return CommandScope.shared(LockSet.builder().users(customer.id()).build());
    }

    private static <R> Command<R> command(CommandScope scope, Function<CommandContext, R> body) {
        return new Command<>() {
            @Override
            public EligibilityCategory eligibilityCategory() {
                return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
            }

            @Override
            public CommandScope scope() {
                return scope;
            }

            @Override
            public R execute(CommandContext context) {
                return body.apply(context);
            }
        };
    }

    private void assertKeyReused(Runnable duplicate) {
        assertThatThrownBy(duplicate::run)
                .isInstanceOfSatisfying(DataAccessException.class, e -> assertThat(DatabaseErrorCodes.translate(e))
                        .contains(ApiErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    private static AuthenticatedSubject customer() {
        return new AuthenticatedSubject(AuthSubjectType.CUSTOMER, Fixtures.aUser().verified().create().id());
    }

    private long events() {
        return db.sql("SELECT count(*) FROM technical.outbox_event WHERE event_type = :type")
                .param("type", eventType).query(Long.class).single();
    }

    private long recordCount(AuthenticatedSubject principal, String endpoint, UUID key) {
        return db.sql("""
                        SELECT count(*) FROM technical.idempotency_record
                         WHERE principal_type = :type AND principal_id = :id AND endpoint = :endpoint
                           AND idempotency_key = :key""")
                .param("type", principal.type().name()).param("id", principal.id()).param("endpoint", endpoint)
                .param("key", key.toString()).query(Long.class).single();
    }

    private Map<String, Object> record(AuthenticatedSubject principal, String endpoint, UUID key) {
        return db.sql("""
                        SELECT principal_type, principal_id, endpoint, idempotency_key, request_fingerprint, state,
                               response_status, response_body::text AS response_body, result_aggregate_type,
                               result_aggregate_id, correlation_id, created_at, completed_at, expires_at
                          FROM technical.idempotency_record
                         WHERE principal_type = :type AND principal_id = :id AND endpoint = :endpoint
                           AND idempotency_key = :key""")
                .param("type", principal.type().name()).param("id", principal.id()).param("endpoint", endpoint)
                .param("key", key.toString()).query().singleRow();
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static Instant instant(Object timestamp) {
        return timestamp instanceof OffsetDateTime odt ? odt.toInstant() : ((Timestamp) timestamp).toInstant();
    }

    private static JdbcClient ownerDb() {
        return JdbcClient.create(new SimpleDriverDataSource(new Driver(),
                PostgresTestContainer.jdbcUrl(PostgresTestContainer.DATABASE), PostgresTestContainer.MIGRATOR,
                PostgresTestContainer.MIGRATOR));
    }
}
