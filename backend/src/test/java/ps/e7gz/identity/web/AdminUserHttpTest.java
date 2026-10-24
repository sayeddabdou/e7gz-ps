package ps.e7gz.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Consumer;
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
import ps.e7gz.support.fixtures.TrustStates;

/**
 * E7-66 PR 2 (PO decision D4, 17 Oct 2026; FR-ADM-04) - {@code GET /admin/users} and {@code GET /admin/users/{userId}}
 * through the real security chain and database: the FR-ADM-04 columns, newest first with keyset paging, the shared
 * customer search, and the detail with no-show standing, the ban in force, Verified Player, points and wallet. Bans
 * and Verified Players have no command yet (E7-71, E7-72), so their trust rows are planted ({@link TrustStates}).
 */
@AutoConfigureMockMvc
class AdminUserHttpTest extends IntegrationTest {

    static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ObjectMapper json;

    private String admin;

    @BeforeEach
    void signIn() {
        admin = "Bearer " + accessTokens.issue(AuthSubjectType.ADMIN, Fixtures.anAdmin().create().id());
    }

    @Test
    void theListIsNewestFirstWithKeysetPagesAndTheFrAdm04Columns() throws Exception {
        String token = "usr" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = TrustStates.dbNow().truncatedTo(ChronoUnit.SECONDS);
        Fixtures.User oldest = Fixtures.aUser().fullName("Oldest " + token).verified().create();
        Fixtures.User middle = Fixtures.aUser().fullName("Middle " + token).create();
        Fixtures.User newest = Fixtures.aUser().fullName("Newest " + token).verified().create();
        joinedAt(oldest.id(), now.minus(Duration.ofDays(3)));
        joinedAt(middle.id(), now.minus(Duration.ofDays(2)));
        joinedAt(newest.id(), now.minus(Duration.ofDays(1)));

        JsonNode first = data(mvc.perform(get("/api/v1/admin/users").param("q", token).param("limit", "2")
                .header("Authorization", admin)).andExpect(status().isOk()));
        assertThat(first.get("items")).extracting(item -> item.get("userId").asText())
                .containsExactly(newest.id().toString(), middle.id().toString());
        JsonNode item = first.get("items").get(0);
        assertThat(item.get("fullName").asText()).isEqualTo("Newest " + token);
        assertThat(item.get("username").asText()).isEqualTo(newest.username());
        assertThat(item.get("phone").asText()).isEqualTo(newest.phoneE164());
        assertThat(item.get("email").asText()).isEqualTo("customer@example.com");
        assertThat(item.get("phoneVerified").asBoolean()).isTrue();
        assertThat(item.get("verifiedPlayerStatus").asText()).isEqualTo("NOT_VERIFIED");
        assertThat(item.get("completedBookings").asInt()).isZero();
        assertThat(item.get("consecutiveNoShows").asInt()).isZero();
        assertThat(item.get("penaltyStage").asInt()).isZero();
        assertThat(item.get("banned").asBoolean()).isFalse();
        assertThat(item.get("deleted").asBoolean()).isFalse();
        assertThat(Instant.parse(item.get("joinedAt").asText())).isEqualTo(now.minus(Duration.ofDays(1)));
        assertThat(first.get("items").get(1).get("phoneVerified").asBoolean()).isFalse();

        JsonNode second = data(mvc.perform(get("/api/v1/admin/users").param("q", token).param("limit", "2")
                .param("cursor", first.get("nextCursor").asText()).header("Authorization", admin))
                .andExpect(status().isOk()));
        assertThat(second.get("items")).extracting(next -> next.get("userId").asText())
                .containsExactly(oldest.id().toString());
        assertThat(second.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void theSearchMatchesUsernameEmailOrTheExactPhoneAndNothingElse() throws Exception {
        String token = "srch" + UUID.randomUUID().toString().substring(0, 8);
        Fixtures.User byUsername = Fixtures.aUser().username(token + "_u").create();
        Fixtures.User byEmail = Fixtures.aUser().email(token + "@mail.test").create();
        Fixtures.User byPhone = Fixtures.aUser().create();

        onlyFinds(token.toUpperCase() + "_U", byUsername.id());
        onlyFinds(token + "@MAIL", byEmail.id());
        onlyFinds(" " + byPhone.localPhone() + " ", byPhone.id());
        onlyFinds("+2" + byPhone.localPhone(), byPhone.id());
        mvc.perform(get("/api/v1/admin/users").param("q", UUID.randomUUID().toString()).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));
    }

    @Test
    void theDetailShowsStandingTheBanInForceVerifiedPlayerAndBalances() throws Exception {
        Fixtures.User user = Fixtures.aUser().fullName("Omar Hassan").verified().create();
        // the real points adjustment first: the planted trust rows below allow read-only use only
        mvc.perform(post("/api/v1/admin/points/adjust").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", admin)
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .content("{\"userId\":\"" + user.id() + "\",\"amount\":450,\"reason\":\"Welcome.\"}"))
                .andExpect(status().isOk());
        TrustStates.projection(user.id(), 1, 1, 6, "VERIFIED");
        Instant now = TrustStates.dbNow();
        Instant activated = now.minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);
        Instant ends = now.plus(Duration.ofDays(7)).truncatedTo(ChronoUnit.SECONDS);
        UUID ban = TrustStates.ban(user.id(), 1, "ACTIVE", activated, ends);
        String referralCode = OWNER.sql("SELECT referral_code FROM iam.user_account WHERE id = :id")
                .param("id", user.id()).query(String.class).single();

        mvc.perform(get("/api/v1/admin/users/" + user.id()).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.userId").value(user.id().toString()))
                .andExpect(jsonPath("$.data.user.fullName").value("Omar Hassan"))
                .andExpect(jsonPath("$.data.user.consecutiveNoShows").value(1))
                .andExpect(jsonPath("$.data.user.penaltyStage").value(1))
                .andExpect(jsonPath("$.data.user.banned").value(true))
                .andExpect(jsonPath("$.data.user.verifiedPlayerStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.data.user.completedBookings").value(6))
                .andExpect(jsonPath("$.data.emailVerified").value(false))
                .andExpect(jsonPath("$.data.currentThreshold").value(2))
                .andExpect(jsonPath("$.data.activeBan.banId").value(ban.toString()))
                .andExpect(jsonPath("$.data.activeBan.penaltyStage").value(1))
                .andExpect(jsonPath("$.data.activeBan.activatedAt").value(activated.toString()))
                .andExpect(jsonPath("$.data.activeBan.endsAt").value(ends.toString()))
                .andExpect(jsonPath("$.data.verifiedPlayerQualification").value("NOT_QUALIFIED"))
                .andExpect(jsonPath("$.data.pointsBalance").value(450))
                .andExpect(jsonPath("$.data.walletBalance.amount").value("0.00"))
                .andExpect(jsonPath("$.data.walletBalance.currency").value("EGP"))
                .andExpect(jsonPath("$.data.referralCode").value(referralCode));
    }

    @Test
    void aCustomerWithoutTrustHistoryHasNoBanAndTheStageZeroThreshold() throws Exception {
        UUID user = Fixtures.aUser().create().id();

        mvc.perform(get("/api/v1/admin/users/" + user).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeBan").value(nullValue()))
                .andExpect(jsonPath("$.data.currentThreshold").value(3))
                .andExpect(jsonPath("$.data.user.banned").value(false))
                .andExpect(jsonPath("$.data.verifiedPlayerQualification").value("NOT_QUALIFIED"))
                .andExpect(jsonPath("$.data.pointsBalance").value(0));
    }

    @Test
    void anAnonymisedAccountIsListedAsDeletedWithoutItsPersonalData() throws Exception {
        UUID user = Fixtures.aUser().verified().create().id();
        anonymise(user);

        mvc.perform(get("/api/v1/admin/users/" + user).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.deleted").value(true))
                .andExpect(jsonPath("$.data.user.fullName").value(""))
                .andExpect(jsonPath("$.data.user.email").value(""))
                .andExpect(jsonPath("$.data.user.phone").value(""));
    }

    @Test
    void anUnknownUserIsNotFoundAndBadPagingIsAValidationFailure() throws Exception {
        mvc.perform(get("/api/v1/admin/users/" + UUID.randomUUID()).header("Authorization", admin))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/admin/users").param("cursor", "not-a-cursor").header("Authorization", admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("cursor"));
        mvc.perform(get("/api/v1/admin/users").param("limit", "101").header("Authorization", admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void onlyThePlatformAdminReadsUsers() throws Exception {
        String customer = "Bearer " + accessTokens.issue(AuthSubjectType.CUSTOMER,
                Fixtures.aUser().verified().create().id());

        mvc.perform(get("/api/v1/admin/users").header("Authorization", customer))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private void onlyFinds(String q, UUID expected) throws Exception {
        JsonNode page = data(mvc.perform(get("/api/v1/admin/users").param("q", q).header("Authorization", admin))
                .andExpect(status().isOk()));
        assertThat(page.get("items")).as("q=%s", q).extracting(item -> item.get("userId").asText())
                .containsExactly(expected.toString());
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private static void joinedAt(UUID user, Instant at) {
        withoutTriggers(jdbc -> jdbc.sql("UPDATE iam.user_account SET joined_at = :at WHERE id = :id")
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).param("id", user).update());
    }

    /** FR-ACC-10, as Sprint 7's anonymisation will leave the row ({@code ck_user_anonymised_pii}), past its guards. */
    private static void anonymise(UUID user) {
        withoutTriggers(jdbc -> jdbc.sql("""
                        UPDATE iam.user_account
                           SET full_name = '', email = '', password_hash = '',
                               phone_e164 = 'anonymised:' || id::text, anonymised_at = now()
                         WHERE id = :id""")
                .param("id", user).update());
    }

    private static void withoutTriggers(Consumer<JdbcClient> writes) {
        SingleConnectionDataSource owner = new SingleConnectionDataSource(PostgresTestContainer.jdbcUrl(DATABASE),
                MIGRATOR, MIGRATOR, true);
        try {
            JdbcClient jdbc = JdbcClient.create(owner);
            jdbc.sql("SET session_replication_role = replica").update();
            writes.accept(jdbc);
            jdbc.sql("SET session_replication_role = origin").update();
        } finally {
            owner.destroy();
        }
    }
}
