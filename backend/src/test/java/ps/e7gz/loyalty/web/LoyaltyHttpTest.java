package ps.e7gz.loyalty.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-66 - the Sprint 4 points and wallet routes (E7-65 contract) through the real security chain and database: the
 * customer's own points and read-only wallet (FR-PTS-01, FR-PTS-05, PCR-004), the Platform Admin's lists and histories
 * (FR-ADM-09) and the points adjustment (FR-PTS-03, FR-PTS-04, FR-PTS-11; Idempotency-Key and reason, D6).
 */
@AutoConfigureMockMvc
class LoyaltyHttpTest extends IntegrationTest {

    static final String ADJUST = "/api/v1/admin/points/adjust";
    static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ObjectMapper json;

    private Fixtures.Admin admin;
    private String adminToken;

    @BeforeEach
    void signIn() {
        admin = Fixtures.anAdmin().create();
        adminToken = bearer(AuthSubjectType.ADMIN, admin.id());
    }

    // ------------------------------------------------------------------------------------------------- customer

    @Test
    void aNewCustomerHasNoPointsAndAnEmptyWallet() throws Exception {
        // FRS section 2.11: open to every customer state, an unverified one included; PCR-004: 0.00 shown clearly
        String customer = customer(Fixtures.aUser().create().id());

        mvc.perform(get("/api/v1/customer/points").header("Authorization", customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(0))
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));
        mvc.perform(get("/api/v1/customer/wallet").header("Authorization", customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance.amount").value("0.00"))
                .andExpect(jsonPath("$.data.balance.currency").value("EGP"))
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));
    }

    @Test
    void theCustomerSeesAppliedAmountsNewestFirstWithTheBalanceOnEveryPage() throws Exception {
        UUID user = Fixtures.aUser().verified().create().id();
        adjust(user, 300, "Welcome bonus.").andExpect(status().isOk());
        adjust(user, -1000, "Duplicate reward.").andExpect(status().isOk());
        adjust(user, 50, "Goodwill.").andExpect(status().isOk());
        String customer = customer(user);

        JsonNode first = data(mvc.perform(get("/api/v1/customer/points?limit=2").header("Authorization", customer))
                .andExpect(status().isOk()));
        assertThat(first.get("balance").asInt()).isEqualTo(50);
        assertThat(first.get("items")).hasSize(2);
        assertThat(first.get("items").get(0).get("amount").asInt()).isEqualTo(50);
        assertThat(first.get("items").get(0).get("balanceAfter").asInt()).isEqualTo(50);
        assertThat(first.get("items").get(0).get("cause").asText()).isEqualTo("ADMIN");
        // FR-PTS-03: a reversal of 1,000 at 300 shows -300 to the customer; the requested amount is the admin's view
        assertThat(first.get("items").get(1).get("amount").asInt()).isEqualTo(-300);
        assertThat(first.get("items").get(1).get("balanceAfter").asInt()).isZero();
        assertThat(first.get("items").get(1).has("requestedAmount")).isFalse();
        assertThat(first.get("items").get(1).has("unrecoveredAmount")).isFalse();
        assertThat(first.get("nextCursor").isTextual()).isTrue();

        JsonNode second = data(mvc.perform(get("/api/v1/customer/points?limit=2")
                .param("cursor", first.get("nextCursor").asText()).header("Authorization", customer))
                .andExpect(status().isOk()));
        assertThat(second.get("balance").asInt()).as("the balance with every page").isEqualTo(50);
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("items").get(0).get("amount").asInt()).isEqualTo(300);
        assertThat(second.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void aCustomerNeverSeesAnotherCustomersPoints() throws Exception {
        UUID someoneElse = Fixtures.aUser().verified().create().id();
        adjust(someoneElse, 100, "Goodwill.").andExpect(status().isOk());
        String customer = customer(Fixtures.aUser().verified().create().id());
        String theirCursor = cursorOf(firstTransaction(someoneElse));

        mvc.perform(get("/api/v1/customer/points").header("Authorization", customer))
                .andExpect(jsonPath("$.data.balance").value(0)).andExpect(jsonPath("$.data.items", hasSize(0)));
        // another customer's transaction as a cursor reaches nothing of theirs
        mvc.perform(get("/api/v1/customer/points?cursor=" + theirCursor).header("Authorization", customer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    void aMalformedCursorIsAValidationFailure() throws Exception {
        String customer = customer(Fixtures.aUser().create().id());

        for (String path : List.of("/api/v1/customer/points", "/api/v1/customer/wallet")) {
            mvc.perform(get(path + "?cursor=not-a-cursor").header("Authorization", customer))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.field").value("cursor"));
        }
    }

    // ------------------------------------------------------------------------------------------ points adjustment

    @Test
    void aDeductionBeyondTheBalanceAppliesTheBalanceAndRecordsTheShortfallNeverADebt() throws Exception {
        UUID user = Fixtures.aUser().verified().create().id();
        adjust(user, 300, "Welcome bonus.").andExpect(status().isOk());

        adjust(user, -1000, "  Duplicate reward.  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(user.toString()))
                .andExpect(jsonPath("$.data.cause").value("ADMIN"))
                .andExpect(jsonPath("$.data.requestedAmount").value(-1000))
                .andExpect(jsonPath("$.data.amount").value(-300))
                .andExpect(jsonPath("$.data.unrecoveredAmount").value(700))
                .andExpect(jsonPath("$.data.balanceAfter").value(0))
                .andExpect(jsonPath("$.data.adminId").value(admin.id().toString()))
                .andExpect(jsonPath("$.data.reason").value("Duplicate reward."))
                .andExpect(jsonPath("$.data.bookingId").value(nullValue()))
                .andExpect(jsonPath("$.data.referralId").value(nullValue()));
        // FR-PTS-04: later points are not used to repay the 700
        adjust(user, 200, "Goodwill.").andExpect(jsonPath("$.data.balanceAfter").value(200));

        assertThat(OWNER.sql("SELECT balance FROM loyalty.points_account WHERE user_id = :id").param("id", user)
                .query(Integer.class).single()).isEqualTo(200);
        assertThat(OWNER.sql("""
                        SELECT actor_type || ' ' || actor_id || ' ' || reason FROM audit.audit_event
                         WHERE action = 'UNRECOVERED_REVERSAL_AMOUNT' AND subject_id = :id""")
                .param("id", user).query(String.class).list())
                .containsExactly("ADMIN " + admin.id() + " Duplicate reward.");
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM audit.audit_event
                         WHERE action = 'POINTS_TRANSACTION_POSTED' AND subject_id = :id AND actor_type = 'ADMIN'""")
                .param("id", user).query(Integer.class).single()).isEqualTo(3);
        // FR-NTF-11: each posting asks for the customer's notification, after commit
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM technical.outbox_event
                         WHERE event_type = 'LOYALTY_POINTS_LEDGER_COMMITTED' AND payload->>'recipientUserId' = :id""")
                .param("id", user.toString()).query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void zeroAMissingReasonOrAMissingKeyIsRefusedAndPostsNothing() throws Exception {
        UUID user = Fixtures.aUser().verified().create().id();

        adjust(user, 0, "Nothing.").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("amount"));
        mvc.perform(post(ADJUST).contentType(MediaType.APPLICATION_JSON).header("Authorization", adminToken)
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .content("{\"userId\":\"" + user + "\",\"amount\":10,\"reason\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("reason"));
        mvc.perform(post(ADJUST).contentType(MediaType.APPLICATION_JSON).header("Authorization", adminToken)
                        .content(body(user, 10, "No key.")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(transactions(user)).isZero();
    }

    @Test
    void anUnknownUserIsNotFoundAndAnAnonymisedOneIsDeleted() throws Exception {
        adjust(UUID.randomUUID(), 10, "Goodwill.").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        UUID deleted = Fixtures.aUser().verified().create().id();
        anonymise(deleted);
        adjust(deleted, 10, "Goodwill.").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_DELETED"));
        assertThat(transactions(deleted)).isZero();
    }

    @Test
    void anExactReplayAnswersTheSameAndPostsOnceAndAChangedRequestIsRefused() throws Exception {
        UUID user = Fixtures.aUser().verified().create().id();
        UUID key = UUID.randomUUID();

        JsonNode first = data(adjust(user, 100, "Goodwill.", key).andExpect(status().isOk()));
        JsonNode replay = data(adjust(user, 100, "Goodwill.", key).andExpect(status().isOk()));
        assertThat(replay).isEqualTo(first);
        assertThat(transactions(user)).isEqualTo(1);

        adjust(user, 200, "Goodwill.", key).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(transactions(user)).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------------ admin reads

    @Test
    void theUserPointsHistoryShowsRequestedAppliedAndUnrecoveredAmounts() throws Exception {
        Fixtures.User user = Fixtures.aUser().fullName("Omar Hassan").verified().create();
        adjust(user.id(), 300, "Welcome bonus.").andExpect(status().isOk());
        adjust(user.id(), -1000, "Duplicate reward.").andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/users/" + user.id() + "/points").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account.userId").value(user.id().toString()))
                .andExpect(jsonPath("$.data.account.fullName").value("Omar Hassan"))
                .andExpect(jsonPath("$.data.account.username").value(user.username()))
                .andExpect(jsonPath("$.data.account.balance").value(0))
                .andExpect(jsonPath("$.data.account.bookingRewards").value(0))
                .andExpect(jsonPath("$.data.account.referralRewards").value(0))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].requestedAmount").value(-1000))
                .andExpect(jsonPath("$.data.items[0].amount").value(-300))
                .andExpect(jsonPath("$.data.items[0].unrecoveredAmount").value(700))
                .andExpect(jsonPath("$.data.items[0].reason").value("Duplicate reward."))
                .andExpect(jsonPath("$.data.items[1].amount").value(300))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));
        mvc.perform(get("/api/v1/admin/users/" + UUID.randomUUID() + "/points").header("Authorization", adminToken))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void thePointsListFindsByNameHighestBalanceFirstAcrossPages() throws Exception {
        String token = "pts" + UUID.randomUUID().toString().substring(0, 8);
        UUID low = Fixtures.aUser().fullName("Low " + token).create().id();
        UUID high = Fixtures.aUser().fullName("High " + token).create().id();
        UUID middle = Fixtures.aUser().fullName("Middle " + token).create().id();
        adjust(low, 100, "Seed.").andExpect(status().isOk());
        adjust(high, 300, "Seed.").andExpect(status().isOk());
        adjust(middle, 200, "Seed.").andExpect(status().isOk());

        JsonNode first = data(mvc.perform(get("/api/v1/admin/points?limit=2").param("q", token.toUpperCase())
                .header("Authorization", adminToken)).andExpect(status().isOk()));
        assertThat(first.get("items")).extracting(item -> item.get("userId").asText())
                .containsExactly(high.toString(), middle.toString());
        assertThat(first.get("items").get(0).get("balance").asInt()).isEqualTo(300);

        JsonNode second = data(mvc.perform(get("/api/v1/admin/points?limit=2").param("q", token)
                .param("cursor", first.get("nextCursor").asText()).header("Authorization", adminToken))
                .andExpect(status().isOk()));
        assertThat(second.get("items")).extracting(item -> item.get("userId").asText())
                .containsExactly(low.toString());
        assertThat(second.get("nextCursor").isNull()).isTrue();

        mvc.perform(get("/api/v1/admin/points").param("q", UUID.randomUUID().toString())
                        .header("Authorization", adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));
    }

    @Test
    void theWalletListFindsByExactPhoneOrEmailAndShowsTheZeroBalance() throws Exception {
        String token = "wal" + UUID.randomUUID().toString().substring(0, 8);
        Fixtures.User byPhone = Fixtures.aUser().fullName("Sara Adel").create();
        Fixtures.User byEmail = Fixtures.aUser().email(token + "@mail.test").create();

        mvc.perform(get("/api/v1/admin/wallet").param("q", " " + byPhone.localPhone() + " ")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].userId").value(byPhone.id().toString()))
                .andExpect(jsonPath("$.data.items[0].fullName").value("Sara Adel"))
                .andExpect(jsonPath("$.data.items[0].phone").value(byPhone.phoneE164()))
                .andExpect(jsonPath("$.data.items[0].balance.amount").value("0.00"));
        mvc.perform(get("/api/v1/admin/wallet").param("q", token + "@MAIL").header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.items[*].userId").value(contains(
                        byEmail.id().toString())));

        mvc.perform(get("/api/v1/admin/users/" + byPhone.id() + "/wallet").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account.userId").value(byPhone.id().toString()))
                .andExpect(jsonPath("$.data.account.balance.amount").value("0.00"))
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));
        mvc.perform(get("/api/v1/admin/users/" + UUID.randomUUID() + "/wallet").header("Authorization", adminToken))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private ResultActions adjust(UUID user, int amount, String reason) throws Exception {
        return adjust(user, amount, reason, UUID.randomUUID());
    }

    private ResultActions adjust(UUID user, int amount, String reason, UUID key) throws Exception {
        return mvc.perform(post(ADJUST).contentType(MediaType.APPLICATION_JSON).header("Authorization", adminToken)
                .header(OpenApiConfig.IDEMPOTENCY_KEY, key.toString()).content(body(user, amount, reason)));
    }

    private static String body(UUID user, int amount, String reason) {
        return "{\"userId\":\"" + user + "\",\"amount\":" + amount + ",\"reason\":\"" + reason + "\"}";
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private String customer(UUID userId) {
        return bearer(AuthSubjectType.CUSTOMER, userId);
    }

    private String bearer(AuthSubjectType type, UUID id) {
        return "Bearer " + accessTokens.issue(type, id);
    }

    private static int transactions(UUID user) {
        return OWNER.sql("SELECT count(*) FROM loyalty.points_transaction WHERE points_account_id = :id")
                .param("id", user).query(Integer.class).single();
    }

    private static UUID firstTransaction(UUID user) {
        return OWNER.sql("""
                        SELECT id FROM loyalty.points_transaction WHERE points_account_id = :id
                         ORDER BY ledger_sequence LIMIT 1""")
                .param("id", user).query(UUID.class).single();
    }

    /** The cursor a page ending at this transaction would answer (16 bytes, URL-safe Base64). */
    private static String cursorOf(UUID transactionId) {
        ByteBuffer bytes = ByteBuffer.allocate(16).putLong(transactionId.getMostSignificantBits())
                .putLong(transactionId.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
    }

    /** FR-ACC-10, as Sprint 7's anonymisation will leave the row ({@code ck_user_anonymised_pii}), past its guards. */
    private static void anonymise(UUID user) {
        SingleConnectionDataSource owner = new SingleConnectionDataSource(PostgresTestContainer.jdbcUrl(DATABASE),
                MIGRATOR, MIGRATOR, true);
        try {
            JdbcClient jdbc = JdbcClient.create(owner);
            jdbc.sql("SET session_replication_role = replica").update();
            jdbc.sql("""
                            UPDATE iam.user_account
                               SET full_name = '', email = '', password_hash = '',
                                   phone_e164 = 'anonymised:' || id::text, anonymised_at = now()
                             WHERE id = :id""")
                    .param("id", user).update();
            jdbc.sql("SET session_replication_role = origin").update();
        } finally {
            owner.destroy();
        }
    }
}
