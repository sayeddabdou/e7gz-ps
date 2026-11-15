package ps.e7gz.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.identity.internal.CustomerRegistrationHttpTest.e164;
import static ps.e7gz.identity.internal.CustomerRegistrationHttpTest.randomLocalPhone;
import static ps.e7gz.identity.internal.CustomerRegistrationHttpTest.randomUsername;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.persistence.DataSourceConfig;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.messaging.internal.OutboxDrain;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-67 - referrals end to end, over HTTP with the real security chain and database (FR-PTS-06 to FR-PTS-09, FR-ADM-09,
 * Product v5.0 §46.4-§46.5): a friend's code at registration makes a PENDING referral; the friend's phone OTP
 * verification rewards the referrer with the referral points in force at that instant, or invalidates a self-referral;
 * an unknown code is recorded INVALID and never blocks the registration; the customer sees their code and their
 * referrals without the friend's identity; the Platform Admin lists them and invalidates one as fraud, taking the exact
 * historical reward back under the zero floor (done when: +1,000 reversed at 300 points leaves 0 and 700 unrecovered).
 */
@AutoConfigureMockMvc
class ReferralHttpTest extends IntegrationTest {

    private static final String AUTH = "/api/v1/customer/auth";
    private static final String ADMIN = "/api/v1/admin";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    OtpPayloadCipher otpCipher;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    DataSource appDataSource;

    @Autowired
    @Qualifier(DataSourceConfig.ADMIN_DATA_SOURCE)
    DataSource adminDataSource;

    final CustomerRegistrationHttpTest.CapturingSmsSender sms = new CustomerRegistrationHttpTest.CapturingSmsSender();

    JdbcClient db;
    String adminToken;
    UUID adminId;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(appDataSource);
        adminId = Fixtures.anAdmin().create().id();
        adminToken = "Bearer " + accessTokens.issue(AuthSubjectType.ADMIN, adminId);
    }

    // ------------------------------------------------------------------------------------------------ the reward

    @Test
    void aFriendsCodeIsPendingUntilTheirOtpThenTheReferrerEarnsTheReferralPoints() throws Exception {
        Customer referrer = verifiedCustomer();
        Registered friend = register(referrer.code().toLowerCase(Locale.ROOT) + "  ");

        assertThat(referral(friend.userId())).isEqualTo("PENDING " + referrer.id() + " null");

        verify(friend).andExpect(status().isOk());

        assertThat(referral(friend.userId())).isEqualTo("REWARDED " + referrer.id() + " 1000");
        assertThat(points(referrer.id())).isEqualTo(1000);
        assertThat(points(friend.userId())).as("only the referrer earns").isZero();
        assertThat(db.sql("""
                        SELECT count(*) FROM technical.outbox_event
                         WHERE event_type = 'LOYALTY_POINTS_LEDGER_COMMITTED'
                           AND payload->>'recipientUserId' = :user AND payload->>'cause' = 'REFERRAL_SIGNUP'""")
                .param("user", referrer.id().toString()).query(Integer.class).single())
                .as("the event that tells the referrer (POINTS_EARNED)").isOne();

        // the customer's view: my code and my referrals, never who signed up
        mvc.perform(get("/api/v1/customer/referrals").header("Authorization", bearer(referrer.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.referralCode").value(referrer.code()))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].status").value("REWARDED"))
                .andExpect(jsonPath("$.data.items[0].rewardPoints").value(1000))
                .andExpect(jsonPath("$.data.items[0].rewardedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].referredUserId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].referredUsername").doesNotExist());
    }

    @Test
    void theRewardIsTheValueInForceAtTheOtpVerificationNotAtTheSignUp() throws Exception {
        Customer referrer = verifiedCustomer();
        Registered friend = register(referrer.code());
        referralPoints(500);
        try {
            verify(friend).andExpect(status().isOk());
        } finally {
            referralPoints(1000);
        }

        assertThat(referral(friend.userId())).isEqualTo("REWARDED " + referrer.id() + " 500");
        assertThat(points(referrer.id())).isEqualTo(500);
    }

    @Test
    void anUnknownCodeAndASelfReferralAreInvalidAndNeverBlockTheRegistration() throws Exception {
        Registered unknown = register("ZZZZ2222");
        assertThat(referral(unknown.userId())).isEqualTo("INVALID null null");
        assertThat(kind(unknown.userId())).isEqualTo("INVALID_CODE");

        Customer referrer = verifiedCustomer();
        Registered self = register(referrer.code(), referrer.email());              // same email: FR-PTS-08
        assertThat(kind(self.userId())).isEqualTo("SELF_REFERRAL");
        verify(self).andExpect(status().isOk());
        assertThat(points(referrer.id())).as("a self-referral earns nothing").isZero();

        Registered none = register(null);
        assertThat(db.sql("SELECT count(*) FROM loyalty.referral WHERE referred_user_id = :id")
                .param("id", none.userId()).query(Integer.class).single()).isZero();
    }

    @Test
    void aFriendVerifiedByAPhoneChangeIsSettledToo() throws Exception {
        Customer referrer = verifiedCustomer();
        Registered friend = register(referrer.code());
        String newLocal = randomLocalPhone();

        JsonNode started = data(mvc.perform(post("/api/v1/customer/profile/phone").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", bearer(friend.userId()))
                .content(json.writeValueAsString(Map.of("phone", newLocal)))).andExpect(status().isCreated()));
        deliverSms();
        mvc.perform(post("/api/v1/customer/profile/phone/verify").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", bearer(friend.userId()))
                        .content(json.writeValueAsString(Map.of("otpChallengeId", started.get("otpChallengeId").asText(),
                                "code", sms.lastCode.get(e164(newLocal))))))
                .andExpect(status().isOk());

        assertThat(referral(friend.userId())).isEqualTo("REWARDED " + referrer.id() + " 1000");
    }

    // -------------------------------------------------------------------------------------------- Platform Admin

    @Test
    void theAdminListFiltersByStatusAndFindsTheReferrerOrTheFriend() throws Exception {
        Customer referrer = verifiedCustomer();
        Registered rewarded = register(referrer.code());
        verify(rewarded).andExpect(status().isOk());
        Registered pending = register(referrer.code());

        JsonNode all = data(mvc.perform(get(ADMIN + "/referrals").param("q", referrer.username())
                .header("Authorization", adminToken)).andExpect(status().isOk()));
        assertThat(all.get("items")).hasSize(2);
        assertThat(all.get("items").get(0).get("referredUserId").asText()).as("newest sign-up first")
                .isEqualTo(pending.userId().toString());
        assertThat(all.get("items").get(0).get("referrerUsername").asText()).isEqualTo(referrer.username());
        assertThat(all.get("items").get(0).get("code").asText()).isEqualTo(referrer.code());

        JsonNode onlyRewarded = data(mvc.perform(get(ADMIN + "/referrals").param("q", referrer.username())
                .param("status", "REWARDED").header("Authorization", adminToken)).andExpect(status().isOk()));
        assertThat(onlyRewarded.get("items")).hasSize(1);
        assertThat(onlyRewarded.get("items").get(0).get("rewardPointsApplied").asInt()).isEqualTo(1000);
        assertThat(onlyRewarded.get("items").get(0).get("referredUsername").asText()).isEqualTo(rewarded.username());

        JsonNode byFriend = data(mvc.perform(get(ADMIN + "/referrals").param("q", pending.username())
                .header("Authorization", adminToken)).andExpect(status().isOk()));
        assertThat(byFriend.get("items")).hasSize(1);
    }

    /** Done when: reversing +1,000 at 300 Points leaves the balance at 0 and records 700 unrecovered (FR-PTS-09). */
    @Test
    void invalidatingARewardedReferralTakesTheExactRewardBackUnderTheZeroFloor() throws Exception {
        Customer referrer = verifiedCustomer();
        Registered friend = register(referrer.code());
        verify(friend).andExpect(status().isOk());
        adjustPoints(referrer.id(), -700);
        assertThat(points(referrer.id())).isEqualTo(300);
        UUID referralId = referralId(friend.userId());
        UUID key = UUID.randomUUID();

        JsonNode done = data(invalidate(referralId, key, "Same person on two phones.").andExpect(status().isOk()));

        assertThat(done.at("/referral/status").asText()).isEqualTo("INVALID");
        assertThat(done.at("/referral/invalidationKind").asText()).isEqualTo("ADMIN_FRAUD");
        assertThat(done.at("/reversal/cause").asText()).isEqualTo("COMPENSATION");
        assertThat(done.at("/reversal/requestedAmount").asInt()).isEqualTo(-1000);
        assertThat(done.at("/reversal/amount").asInt()).isEqualTo(-300);
        assertThat(done.at("/reversal/unrecoveredAmount").asInt()).isEqualTo(700);
        assertThat(done.at("/reversal/balanceAfter").asInt()).isZero();
        assertThat(done.at("/reversal/referralId").asText()).isEqualTo(referralId.toString());
        assertThat(points(referrer.id())).isZero();
        // the 700 is never a debt: points earned later are not used to repay it (FR-PTS-04)
        adjustPoints(referrer.id(), 100);
        assertThat(points(referrer.id())).isEqualTo(100);

        // an exact replay answers the same; a new request on an INVALID referral is refused
        assertThat(data(invalidate(referralId, key, "Same person on two phones.").andExpect(status().isOk())))
                .isEqualTo(done);
        invalidate(referralId, UUID.randomUUID(), "Again.").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMIN_ACTION_NOT_ALLOWED"));
        assertThat(db.sql("""
                        SELECT count(*) FROM loyalty.points_transaction WHERE referral_id = :id""")
                .param("id", referralId).query(Integer.class).single()).as("one reward, one reversal").isEqualTo(2);
        assertThat(db.sql("""
                        SELECT count(*) FROM technical.outbox_event
                         WHERE event_type = 'LOYALTY_POINTS_LEDGER_COMMITTED' AND payload->>'cause' = 'COMPENSATION'
                           AND payload->>'referralId' = :id""")
                .param("id", referralId.toString()).query(Integer.class).single())
                .as("the event that tells the referrer (ADMIN_ACCOUNT_CHANGE)").isOne();
    }

    @Test
    void invalidatingAPendingReferralTakesNothingBackAndItIsNeverRewarded() throws Exception {
        Customer referrer = verifiedCustomer();
        Registered friend = register(referrer.code());
        UUID referralId = referralId(friend.userId());

        JsonNode done = data(invalidate(referralId, UUID.randomUUID(), "Bulk sign-ups.").andExpect(status().isOk()));
        assertThat(done.at("/referral/status").asText()).isEqualTo("INVALID");
        assertThat(done.get("reversal").isNull()).isTrue();

        verify(friend).andExpect(status().isOk());
        assertThat(points(referrer.id())).isZero();

        invalidate(UUID.randomUUID(), UUID.randomUUID(), "Unknown.").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    record Customer(UUID id, String username, String email, String code) {
    }

    record Registered(UUID userId, UUID challengeId, String phoneE164, String username) {
    }

    private Customer verifiedCustomer() throws Exception {
        String email = randomUsername() + "@example.com";
        Registered registered = register(null, email);
        verify(registered).andExpect(status().isOk());
        String code = db.sql("SELECT referral_code FROM iam.user_account WHERE id = :id")
                .param("id", registered.userId()).query(String.class).single();
        return new Customer(registered.userId(), registered.username(), email, code);
    }

    private Registered register(String referralCode) throws Exception {
        return register(referralCode, randomUsername() + "@example.com");
    }

    private Registered register(String referralCode, String email) throws Exception {
        String local = randomLocalPhone();
        String username = randomUsername();
        Map<String, String> body = new HashMap<>(Map.of("fullName", "Friend", "username", username, "phone", local,
                "email", email, "password", "Passw0rd1"));
        if (referralCode != null) {
            body.put("referralCode", referralCode);
        }
        JsonNode data = data(mvc.perform(post(AUTH + "/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isCreated()));
        deliverSms();
        return new Registered(UUID.fromString(data.get("userId").asText()),
                UUID.fromString(data.get("otpChallengeId").asText()), e164(local), username);
    }

    private ResultActions verify(Registered registered) throws Exception {
        return mvc.perform(post(AUTH + "/verify-phone").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("otpChallengeId", registered.challengeId().toString(),
                        "code", sms.lastCode.get(registered.phoneE164())))));
    }

    private ResultActions invalidate(UUID referralId, UUID key, String reason) throws Exception {
        return mvc.perform(post(ADMIN + "/referrals/" + referralId + "/invalidate")
                .contentType(MediaType.APPLICATION_JSON).header("Authorization", adminToken)
                .header(OpenApiConfig.IDEMPOTENCY_KEY, key.toString())
                .content(json.writeValueAsString(Map.of("reason", reason))));
    }

    private void adjustPoints(UUID userId, int amount) throws Exception {
        mvc.perform(post(ADMIN + "/points/adjust").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", adminToken)
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .content(json.writeValueAsString(Map.of("userId", userId.toString(), "amount", amount,
                                "reason", "Spent elsewhere."))))
                .andExpect(status().isOk());
    }

    /** {@code loyalty.referral_points} through its producer, as PUT /admin/points/settings does (E7-68). */
    private void referralPoints(int value) {
        JdbcClient.create(adminDataSource).sql("""
                        SELECT loyalty.configure('loyalty.referral_points', CAST(:value AS jsonb), :admin, :request,
                                                 'E7-67 test')""")
                .param("value", Integer.toString(value)).param("admin", adminId).param("request", UUID.randomUUID())
                .query().singleRow();
    }

    /** What the worker's outbox dispatcher does right after the commit (E7-41). */
    private void deliverSms() {
        OutboxDrain.drain(appDataSource, json, new PhoneOtpSms(otpCipher, sms, appDataSource));
    }

    /** {@code status referrer reward} of the friend's referral. */
    private String referral(UUID referredUserId) {
        return db.sql("""
                        SELECT status || ' ' || COALESCE(referrer_user_id::text, 'null') || ' '
                               || COALESCE(reward_value_applied::text, 'null')
                          FROM loyalty.referral WHERE referred_user_id = :id""")
                .param("id", referredUserId).query(String.class).single();
    }

    private String kind(UUID referredUserId) {
        return db.sql("SELECT invalidation_kind FROM loyalty.referral WHERE referred_user_id = :id")
                .param("id", referredUserId).query(String.class).single();
    }

    private UUID referralId(UUID referredUserId) {
        return db.sql("SELECT id FROM loyalty.referral WHERE referred_user_id = :id")
                .param("id", referredUserId).query(UUID.class).single();
    }

    private int points(UUID userId) {
        return db.sql("SELECT balance FROM loyalty.points_account WHERE user_id = :id")
                .param("id", userId).query(Integer.class).single();
    }

    private String bearer(UUID customerId) {
        return "Bearer " + accessTokens.issue(AuthSubjectType.CUSTOMER, customerId);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }
}
