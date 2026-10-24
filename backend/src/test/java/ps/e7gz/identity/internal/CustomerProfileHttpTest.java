package ps.e7gz.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.identity.internal.TestCustomers.randomLocalPhone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.messaging.internal.OutboxDrain;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-57 (Spec v5 §7.5, §7.6; FR-ACC-06…09; contract E7-54): the customer's profile - full name, username with its
 * 30-day cooldown, the two-step phone change - and the forgotten-password reset by phone OTP, through the real
 * security chain, error mapping and database. Done when: the cooldown error is mapped, and the tokens are revoked on a
 * password reset.
 */
@AutoConfigureMockMvc
class CustomerProfileHttpTest extends IntegrationTest {

    private static final String PROFILE = "/api/v1/customer/profile";
    private static final String AUTH = "/api/v1/customer/auth";
    private static final String PASSWORD = "Passw0rd1";
    private static final String NEW_PASSWORD = "N3wPassword";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    OtpPayloadCipher otpCipher;

    @Autowired
    DataSource appDataSource;

    final CustomerRegistrationHttpTest.CapturingSmsSender sms = new CustomerRegistrationHttpTest.CapturingSmsSender();

    JdbcClient db;
    String passwordHash;
    TestCustomers.Customer me;
    String token;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(appDataSource);
        if (passwordHash == null) {
            passwordHash = passwords.encode(PASSWORD);
        }
        me = TestCustomers.insert(db, true, passwordHash);
        token = accessTokens.issue(AuthSubjectType.CUSTOMER, me.id());
    }

    // ------------------------------------------------------------------------------------------------ profile

    @Test
    void myProfileShowsWhatICanSeeAndEdit() throws Exception {
        mvc.perform(authed(get(PROFILE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(me.id().toString()))
                .andExpect(jsonPath("$.data.username").value(me.username()))
                .andExpect(jsonPath("$.data.phone").value(me.phoneE164()))
                .andExpect(jsonPath("$.data.phoneVerified").value(true))
                .andExpect(jsonPath("$.data.emailVerified").value(false))
                .andExpect(jsonPath("$.data.usernameChangeAvailableAt").isEmpty())
                .andExpect(jsonPath("$.data.joinedAt").isNotEmpty());
    }

    @Test
    void theFullNameChangesFreely() throws Exception {
        mvc.perform(authed(json(patch(PROFILE), Map.of("fullName", "  Omar Hassan  "))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fullName").value("Omar Hassan"));
        mvc.perform(authed(json(patch(PROFILE), Map.of("fullName", "Omar H."))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fullName").value("Omar H."));
        mvc.perform(authed(json(patch(PROFILE), Map.of("fullName", "   "))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /** Done when (E7-57): the database's 30-day cooldown reaches the client as USERNAME_COOLDOWN_ACTIVE. */
    @Test
    void aSecondUsernameChangeWithin30DaysIsTheCooldownAndTheProfileSaysWhenItEnds() throws Exception {
        String first = randomUsername();
        JsonNode changed = body(mvc.perform(authed(json(post(PROFILE + "/username"), Map.of("username", first))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value(first)));
        Instant availableAt = Instant.parse(changed.path("data").path("usernameChangeAvailableAt").asText());

        mvc.perform(authed(json(post(PROFILE + "/username"), Map.of("username", randomUsername()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("USERNAME_COOLDOWN_ACTIVE"));

        // gd_username_cooldown's own sum: "30 days" on the database's calendar (across a DST change it is not 720 h)
        Instant cooldownEnd = db.sql("""
                        SELECT changed_at + interval '30 days' FROM iam.username_change WHERE user_id = :id""")
                .param("id", me.id()).query(OffsetDateTime.class).single().toInstant();
        assertThat(availableAt).isEqualTo(cooldownEnd);
        mvc.perform(authed(get(PROFILE))).andExpect(jsonPath("$.data.username").value(first));
    }

    @Test
    void aUsernameAnotherAccountHoldsIsTakenTrimmedAndCaseInsensitive() throws Exception {
        TestCustomers.Customer other = TestCustomers.insert(db, true, passwordHash);

        mvc.perform(authed(json(post(PROFILE + "/username"),
                        Map.of("username", " " + other.username().toUpperCase()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("USERNAME_TAKEN"));
        assertThat(usernameChanges(me.id())).isZero();
    }

    @Test
    void myOwnUsernameChangesNothingAndStartsNoCooldown() throws Exception {
        mvc.perform(authed(json(post(PROFILE + "/username"), Map.of("username", me.username()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.usernameChangeAvailableAt").isEmpty());

        assertThat(usernameChanges(me.id())).isZero();
    }

    // ------------------------------------------------------------------------------------------------ phone

    @Test
    void thePhoneChangesOnlyOnceTheNewOneIsVerifiedAndTheCustomerIsTold() throws Exception {
        String local = randomLocalPhone();
        String phone = PhoneNumbers.toE164(local).orElseThrow();
        UUID challenge = challengeId(startPhoneChange(local).andExpect(status().isCreated()));

        mvc.perform(authed(get(PROFILE))).andExpect(jsonPath("$.data.phone").value(me.phoneE164()));
        mvc.perform(authed(json(post(PROFILE + "/phone/verify"), code(challenge, sms.lastCode.get(phone)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value(phone))
                .andExpect(jsonPath("$.data.phoneVerified").value(true));

        assertThat(notifications(me.id(), "PHONE_CHANGED")).isOne();
        login(local, PASSWORD).andExpect(status().isOk());                        // the new phone logs in
    }

    @Test
    void aWrongCodeKeepsTheOldPhoneAndCountsItsAttempt() throws Exception {
        String local = randomLocalPhone();
        String phone = PhoneNumbers.toE164(local).orElseThrow();
        UUID challenge = challengeId(startPhoneChange(local));

        mvc.perform(authed(json(post(PROFILE + "/phone/verify"), code(challenge, wrong(sms.lastCode.get(phone))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("OTP_INVALID"));

        assertThat(db.sql("SELECT attempts FROM iam.otp_challenge WHERE id = :id").param("id", challenge)
                .query(Integer.class).single()).isOne();
        mvc.perform(authed(get(PROFILE))).andExpect(jsonPath("$.data.phone").value(me.phoneE164()));
    }

    @Test
    void anotherCustomersPhoneChangeIsNotFound() throws Exception {
        UUID challenge = challengeId(startPhoneChange(randomLocalPhone()));
        String otherToken = accessTokens.issue(AuthSubjectType.CUSTOMER,
                TestCustomers.insert(db, true, passwordHash).id());

        mvc.perform(json(post(PROFILE + "/phone/verify"), code(challenge, "123456"))
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void aPhoneOfAnotherVerifiedAccountIsTakenAndMyOwnIsAValidationFailure() throws Exception {
        TestCustomers.Customer other = TestCustomers.insert(db, true, passwordHash);

        startPhoneChange(other.localPhone()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("PHONE_TAKEN"));
        startPhoneChange(me.localPhone()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("phone"));
    }

    /**
     * Sprint 0 review S-3: an account that never verified the phone gives it up when I verify it, and its phones stop
     * getting push (E7-119). My own phone change ends none of my sessions.
     */
    @Test
    void aPhoneHeldByAnUnverifiedAccountIsTakenOverOnVerification() throws Exception {
        TestCustomers.Customer squatter = TestCustomers.insert(db, false, passwordHash);
        TestCustomers.insertPushDevice(db, squatter.id());
        TestCustomers.insertPushDevice(db, me.id());
        UUID challenge = challengeId(startPhoneChange(squatter.localPhone()).andExpect(status().isCreated()));

        mvc.perform(authed(json(post(PROFILE + "/phone/verify"),
                        code(challenge, sms.lastCode.get(squatter.phoneE164())))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.phone").value(squatter.phoneE164()));

        assertThat(phoneOf(squatter.id())).isEqualTo("released:" + squatter.id());
        login(squatter.username(), PASSWORD).andExpect(status().isUnauthorized());
        assertThat(TestCustomers.activePushDevices(db, squatter.id())).isZero();
        assertThat(TestCustomers.activePushDevices(db, me.id())).isOne();
    }

    // ------------------------------------------------------------------------------------------------ password reset

    /** Done when (E7-57): after a reset the old refresh tokens no longer work. */
    @Test
    void aPasswordResetSetsTheNewPasswordAndEndsEverySession() throws Exception {
        String refresh = body(login(me.localPhone(), PASSWORD).andExpect(status().isOk()))
                .path("data").path("refreshToken").asText();
        UUID challenge = challengeId(requestReset(me.localPhone()).andExpect(status().isCreated()));

        mvc.perform(json(post(AUTH + "/password-reset/complete"),
                        Map.of("otpChallengeId", challenge, "code", sms.lastCode.get(me.phoneE164()),
                                "password", NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        mvc.perform(json(post(AUTH + "/refresh"), Map.of("refreshToken", refresh)))
                .andExpect(status().isUnauthorized());
        login(me.localPhone(), PASSWORD).andExpect(status().isUnauthorized());
        login(me.localPhone(), NEW_PASSWORD).andExpect(status().isOk());
        assertThat(notifications(me.id(), "PASSWORD_RESET")).isOne();
    }

    /**
     * E7-119 (PO decision 24 Oct 2026): the reset also stops push on every phone of the account, in the same command.
     * A device revoked before keeps its own revocation time; another customer's phones are untouched.
     */
    @Test
    void aPasswordResetAlsoStopsPushOnEveryPhoneOfTheAccount() throws Exception {
        TestCustomers.insertPushDevice(db, me.id());
        TestCustomers.insertPushDevice(db, me.id());
        UUID earlier = TestCustomers.insertPushDevice(db, me.id());
        db.sql("UPDATE notification.device_token SET revoked_at = last_seen_at WHERE id = :id").param("id", earlier)
                .update();
        OffsetDateTime earlierRevokedAt = revokedAt(earlier);
        TestCustomers.Customer other = TestCustomers.insert(db, true, passwordHash);
        TestCustomers.insertPushDevice(db, other.id());
        UUID challenge = challengeId(requestReset(me.localPhone()).andExpect(status().isCreated()));

        mvc.perform(json(post(AUTH + "/password-reset/complete"),
                        Map.of("otpChallengeId", challenge, "code", sms.lastCode.get(me.phoneE164()),
                                "password", NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        assertThat(TestCustomers.activePushDevices(db, me.id())).isZero();
        assertThat(revokedAt(earlier)).isEqualTo(earlierRevokedAt);
        assertThat(TestCustomers.activePushDevices(db, other.id())).isOne();
    }

    @Test
    void aWeakNewPasswordIsRefusedAndTheCodeStaysUsable() throws Exception {
        UUID challenge = challengeId(requestReset(me.localPhone()));
        String code = sms.lastCode.get(me.phoneE164());

        mvc.perform(json(post(AUTH + "/password-reset/complete"),
                        Map.of("otpChallengeId", challenge, "code", code, "password", "password")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("password"));
        mvc.perform(json(post(AUTH + "/password-reset/complete"),
                        Map.of("otpChallengeId", challenge, "code", code, "password", NEW_PASSWORD)))
                .andExpect(status().isNoContent());
    }

    @Test
    void anUnknownOrUnverifiedPhoneHasNoResetAndAnUnknownCodeIsInvalid() throws Exception {
        requestReset(randomLocalPhone()).andExpect(status().isNotFound());
        requestReset(TestCustomers.insert(db, false, passwordHash).localPhone()).andExpect(status().isNotFound());

        mvc.perform(json(post(AUTH + "/password-reset/complete"),
                        Map.of("otpChallengeId", UUID.randomUUID(), "code", "123456", "password", NEW_PASSWORD)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("OTP_INVALID"));
    }

    @Test
    void aWrongResetCodeChangesNothing() throws Exception {
        TestCustomers.insertPushDevice(db, me.id());
        UUID challenge = challengeId(requestReset(me.localPhone()));

        mvc.perform(json(post(AUTH + "/password-reset/complete"),
                        Map.of("otpChallengeId", challenge, "code", wrong(sms.lastCode.get(me.phoneE164())),
                                "password", NEW_PASSWORD)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("OTP_INVALID"));
        login(me.localPhone(), PASSWORD).andExpect(status().isOk());
        assertThat(TestCustomers.activePushDevices(db, me.id())).isOne();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private ResultActions startPhoneChange(String phone) throws Exception {
        ResultActions result = mvc.perform(authed(json(post(PROFILE + "/phone"), Map.of("phone", phone))));
        deliverSms();
        return result;
    }

    private ResultActions requestReset(String phone) throws Exception {
        ResultActions result = mvc.perform(json(post(AUTH + "/password-reset/request"), Map.of("phone", phone)));
        deliverSms();
        return result;
    }

    private ResultActions login(String usernameOrPhone, String password) throws Exception {
        return mvc.perform(json(post(AUTH + "/login").with(r -> {
            r.setRemoteAddr("10.57." + ThreadLocalRandom.current().nextInt(256) + "."
                    + ThreadLocalRandom.current().nextInt(256));
            return r;
        }), Map.of("usernameOrPhone", usernameOrPhone, "password", password)));
    }

    /** What the worker's outbox dispatcher does right after the commit (E7-41). */
    private void deliverSms() {
        OutboxDrain.drain(appDataSource, json, new PhoneOtpSms(otpCipher, sms, appDataSource));
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + token);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Map<String, ?> body)
            throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    private static Map<String, Object> code(UUID challenge, String code) {
        return Map.of("otpChallengeId", challenge, "code", code);
    }

    private UUID challengeId(ResultActions result) {
        return UUID.fromString(body(result).path("data").path("otpChallengeId").asText());
    }

    private JsonNode body(ResultActions result) {
        try {
            return json.readTree(result.andReturn().getResponse().getContentAsString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String wrong(String code) {
        return "000000".equals(code) ? "000001" : "000000";
    }

    private static String randomUsername() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private long usernameChanges(UUID userId) {
        return db.sql("SELECT count(*) FROM iam.username_change WHERE user_id = :id").param("id", userId)
                .query(Long.class).single();
    }

    private long notifications(UUID userId, String code) {
        return db.sql("""
                        SELECT count(*) FROM notification.notification
                         WHERE recipient_type = 'CUSTOMER' AND recipient_id = :id AND code = :code""")
                .param("id", userId).param("code", code).query(Long.class).single();
    }

    private OffsetDateTime revokedAt(UUID deviceId) {
        return db.sql("SELECT revoked_at FROM notification.device_token WHERE id = :id").param("id", deviceId)
                .query(OffsetDateTime.class).single();
    }

    private String phoneOf(UUID userId) {
        return db.sql("SELECT phone_e164 FROM iam.user_account WHERE id = :id").param("id", userId)
                .query(String.class).single();
    }
}
