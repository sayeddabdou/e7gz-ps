package ps.e7gz.trust.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.trust.internal.VerifiedPlayerFlows.transitions;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;
import ps.e7gz.trust.internal.VerifiedPlayerFlows;

/**
 * E7-72 - the Sprint 4 contract's Verified Player routes (E7-65, PO decisions D5 and D6 of 17 Oct 2026) through the
 * real security chain: the customer's Account Standing, the admin list, and Suspend / Revoke / Restore with their
 * reason, Idempotency-Key replay and refusals. Replaces this task's lines in {@code Sprint4StubHttpTest}.
 */
@AutoConfigureMockMvc
class AdminVerifiedPlayerHttpTest extends IntegrationTest {

    private static final String REASON = "{\"reason\":\"Reported for misuse.\"}";

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ApplicationContext context;

    VerifiedPlayerFlows flows;
    String admin;
    CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        correlation = CorrelationId.open(CorrelationId.generate());
        flows = VerifiedPlayerFlows.of(context);
        admin = "Bearer " + accessTokens.issue(AuthSubjectType.ADMIN, Fixtures.anAdmin().create().id());
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    /** FR-ACC-11, FR-TRS-15: progress "n / 10" and the status as two fields; open to an unverified customer too. */
    @Test
    void theAccountStandingShowsProgressAndStatus() throws Exception {
        UUID unverified = Fixtures.aUser().create().id();
        standing(unverified)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consecutiveNoShows").value(0))
                .andExpect(jsonPath("$.data.penaltyStage").value(0))
                .andExpect(jsonPath("$.data.currentThreshold").value(3))
                .andExpect(jsonPath("$.data.currentBan").value((Object) null))
                .andExpect(jsonPath("$.data.verifiedPlayer.qualification").value("NOT_QUALIFIED"))
                .andExpect(jsonPath("$.data.verifiedPlayer.completedBookings").value(0))
                .andExpect(jsonPath("$.data.verifiedPlayer.requiredBookings").value(10))
                .andExpect(jsonPath("$.data.verifiedPlayer.status").value("NOT_VERIFIED"))
                .andExpect(jsonPath("$.meta.serverTime").exists());

        UUID player = qualified();
        standing(player)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verifiedPlayer.qualification").value("QUALIFIED"))
                .andExpect(jsonPath("$.data.verifiedPlayer.completedBookings").value(10))
                .andExpect(jsonPath("$.data.verifiedPlayer.requiredBookings").value(10))
                .andExpect(jsonPath("$.data.verifiedPlayer.status").value("VERIFIED"));
    }

    @Test
    void suspendRevokeAndRestoreAnswerThePlayer() throws Exception {
        UUID player = qualified();

        call(player, "suspend", REASON, UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(player.toString()))
                .andExpect(jsonPath("$.data.fullName").isNotEmpty())
                .andExpect(jsonPath("$.data.username").isNotEmpty())
                .andExpect(jsonPath("$.data.qualification").value("QUALIFIED"))
                .andExpect(jsonPath("$.data.completedBookings").value(10))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.statusCause").value("ADMIN"))
                .andExpect(jsonPath("$.data.statusSince").isNotEmpty());
        call(player, "restore", REASON, UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VERIFIED"))
                .andExpect(jsonPath("$.data.statusCause").value("ADMIN"));
        call(player, "revoke", REASON, UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));

        assertThat(transitions(player)).extracting(VerifiedPlayerFlows.Transition::to)
                .containsExactly("VERIFIED", "SUSPENDED", "VERIFIED", "REVOKED");
        assertThat(transitions(player)).extracting(VerifiedPlayerFlows.Transition::transitionId)
                .doesNotHaveDuplicates();
    }

    /** ADR-015, PO decision D6: the same key replays the first answer and acts once; another body is refused. */
    @Test
    void aRetryWithTheSameKeyReplaysAndActsOnce() throws Exception {
        UUID player = qualified();
        UUID key = UUID.randomUUID();

        call(player, "suspend", REASON, key).andExpect(status().isOk());
        call(player, "suspend", REASON, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
        call(player, "suspend", "{\"reason\":\"Another reason.\"}", key)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(transitions(player)).hasSize(2);
    }

    /** PO decision D5: suspend / revoke only from VERIFIED, restore only from SUSPENDED or REVOKED. */
    @Test
    void aStatusTheActionDoesNotStartFromIsRefused() throws Exception {
        UUID fresh = Fixtures.aUser().verified().create().id();
        for (String action : List.of("suspend", "revoke", "restore")) {
            notAllowed(call(fresh, action, REASON, UUID.randomUUID()));
        }
        UUID player = qualified();
        notAllowed(call(player, "restore", REASON, UUID.randomUUID()));
        call(player, "revoke", REASON, UUID.randomUUID()).andExpect(status().isOk());
        notAllowed(call(player, "suspend", REASON, UUID.randomUUID()));
        notAllowed(call(player, "revoke", REASON, UUID.randomUUID()));
        assertThat(transitions(fresh)).isEmpty();
        assertThat(transitions(player)).hasSize(2);
    }

    /** D6: reason and Idempotency-Key required; unknown is NOT_FOUND; anonymised is ACCOUNT_DELETED; admins only. */
    @Test
    void theRequestAndTheCustomerAreCheckedBeforeAnythingIsWritten() throws Exception {
        UUID player = qualified();

        call(player, "suspend", "{}", UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("reason"));
        call(player, "suspend", "{\"reason\":\"   \"}", UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(post(path(player, "suspend")).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));               // no Idempotency-Key
        call(UUID.randomUUID(), "suspend", REASON, UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        String customer = "Bearer " + accessTokens.issue(AuthSubjectType.CUSTOMER, player);
        mvc.perform(post(path(player, "suspend")).header("Authorization", customer)
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isForbidden());
        assertThat(transitions(player)).hasSize(1);

        UUID deleted = Fixtures.aUser().verified().create().id();
        anonymise(deleted);
        call(deleted, "revoke", REASON, UUID.randomUUID())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_DELETED"));
    }

    /** Newest status change first; status filters exactly; cursor paging; a malformed cursor is VALIDATION_FAILED. */
    @Test
    void theListPagesNewestStatusChangeFirstAndFiltersByStatus() throws Exception {
        UUID first = qualified();
        UUID second = qualified();
        call(first, "suspend", REASON, UUID.randomUUID()).andExpect(status().isOk());       // first changed last

        String page1 = mvc.perform(get("/api/v1/admin/verified-players?limit=1").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].userId").value(first.toString()))
                .andExpect(jsonPath("$.data.items[0].status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.nextCursor").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(page1, "$.data.nextCursor");
        mvc.perform(get("/api/v1/admin/verified-players?limit=1&cursor=" + cursor).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].userId").value(second.toString()))
                .andExpect(jsonPath("$.data.items[0].status").value("VERIFIED"))
                .andExpect(jsonPath("$.data.items[0].statusCause").value("TRUST_QUALIFICATION"));

        String suspended = mvc.perform(get("/api/v1/admin/verified-players?status=SUSPENDED&limit=100")
                        .header("Authorization", admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> statuses = JsonPath.read(suspended, "$.data.items[*].status");
        List<String> ids = JsonPath.read(suspended, "$.data.items[*].userId");
        assertThat(statuses).containsOnly("SUSPENDED");
        assertThat(ids).contains(first.toString()).doesNotContain(second.toString());

        mvc.perform(get("/api/v1/admin/verified-players?cursor=not-a-cursor").header("Authorization", admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("cursor"));
    }

    // ------------------------------------------------------------------------------------------------- support

    /** A customer with ten ATTENDED completions: QUALIFIED and VERIFIED through the real commands. */
    private UUID qualified() {
        List<Booking> ten = flows.attendedCompletions(10);
        return ten.getFirst().customerId();
    }

    private ResultActions standing(UUID customer) throws Exception {
        return mvc.perform(get("/api/v1/customer/account-standing")
                .header("Authorization", "Bearer " + accessTokens.issue(AuthSubjectType.CUSTOMER, customer)));
    }

    private ResultActions call(UUID userId, String action, String body, UUID key) throws Exception {
        return mvc.perform(post(path(userId, action)).header("Authorization", admin)
                .header(OpenApiConfig.IDEMPOTENCY_KEY, key.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static void notAllowed(ResultActions result) throws Exception {
        result.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ADMIN_ACTION_NOT_ALLOWED"));
    }

    private static String path(UUID userId, String action) {
        return "/api/v1/admin/verified-players/" + userId + "/" + action;
    }

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
