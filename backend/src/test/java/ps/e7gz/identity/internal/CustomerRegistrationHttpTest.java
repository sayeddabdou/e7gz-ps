package ps.e7gz.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ps.e7gz.identity.internal.sms.SmsDeliveryException;
import ps.e7gz.identity.internal.sms.SmsSender;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.messaging.internal.OutboxDrain;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-13 - registration, phone OTP and verification over HTTP (with the real security chain) against the migrated
 * database. Each FR-ACC-04 limit returns its code; the limits themselves are enforced by the database.
 *
 * <p>E7-41: the code leaves through the outbox. The test profile runs no dispatcher, so every request that issues a
 * code is followed by a delivery of the due {@code PhoneOtpIssued} events to {@link PhoneOtpSms} with a capturing SMS
 * sender, as the worker would do right after the commit.
 */
@AutoConfigureMockMvc
class CustomerRegistrationHttpTest extends IntegrationTest {

    private static final String AUTH = "/api/v1/customer/auth";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    OtpPayloadCipher otpCipher;

    final CapturingSmsSender sms = new CapturingSmsSender();

    @Autowired
    DataSource appDataSource;

    JdbcClient db;

    /** Replaces the SMS provider: keeps the last code per phone, or fails on demand. */
    static class CapturingSmsSender implements SmsSender {

        final Map<String, String> lastCode = new ConcurrentHashMap<>();
        final AtomicBoolean failing = new AtomicBoolean();

        @Override
        public void sendOtp(String phoneE164, String code) {
            if (failing.get()) {
                throw new SmsDeliveryException("test failure", "4906", null);
            }
            lastCode.put(phoneE164, code);
        }
    }

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(appDataSource);
        sms.failing.set(false);
    }

    // ------------------------------------------------------------------------------------------------ registration

    @Test
    void registrationCreatesAnUnverifiedAccountAndSendsAPhoneCode() throws Exception {
        String local = randomLocalPhone();
        JsonNode body = register("  Omar Hassan ", randomUsername(), local, "omar@example.com", "correct horse 9")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().transform(this::read);

        UUID userId = UUID.fromString(body.path("data").path("userId").asText());
        UUID challengeId = UUID.fromString(body.path("data").path("otpChallengeId").asText());
        Map<String, Object> user = db.sql("""
                        SELECT full_name, phone_e164, phone_verified, email_verified, password_hash, referral_code
                          FROM iam.user_account WHERE id = :id""")
                .param("id", userId).query().singleRow();

        assertThat(user.get("full_name")).isEqualTo("Omar Hassan");
        assertThat(user.get("phone_e164")).isEqualTo(e164(local));
        assertThat(user.get("phone_verified")).isEqualTo(false);
        assertThat(user.get("email_verified")).isEqualTo(false);
        assertThat((String) user.get("password_hash")).startsWith("$argon2id$").doesNotContain("correct horse 9");
        assertThat((String) user.get("referral_code")).hasSize(8);

        String code = sms.lastCode.get(e164(local));
        assertThat(code).matches("[0-9]{6}");
        String stored = db.sql("SELECT code_hash FROM iam.otp_challenge WHERE id = :id")
                .param("id", challengeId).query(String.class).single();
        assertThat(stored).doesNotContain(code);
        assertThat(OtpCodes.matches(code, stored)).isTrue();
        assertThat(body.toString()).doesNotContain(code).doesNotContain("correct horse 9");
    }

    @Test
    void theUsernameIsUniqueTrimmedAndCaseInsensitive() throws Exception {
        String username = randomUsername();
        register("A", username, randomLocalPhone(), "a@example.com", "Passw0rd1").andExpect(status().isCreated());

        register("B", "  " + username.toUpperCase() + " ", randomLocalPhone(), "b@example.com", "Passw0rd1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("USERNAME_TAKEN"));
    }

    @Test
    void thePhoneIsUniqueWhateverFormItIsWrittenIn() throws Exception {
        String local = randomLocalPhone();
        register("A", randomUsername(), local, "a@example.com", "Passw0rd1").andExpect(status().isCreated());

        register("B", randomUsername(), "+2" + local, "b@example.com", "Passw0rd1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("PHONE_TAKEN"));
    }

    @Test
    void anInvalidPhoneOrAMissingFieldIsAValidationFailure() throws Exception {
        register("A", randomUsername(), "12345", "a@example.com", "Passw0rd1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("phone"));

        register("A", randomUsername(), randomLocalPhone(), "a@example.com", " ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("password"));
    }

    @Test
    void aPasswordThatBreaksTheRuleIsRefusedAndNothingIsCreated() throws Exception {
        // VALIDATION RULES §2.1: at least 8 characters, at least one letter and one digit.
        for (String weak : new String[] {"abc123", "abcdefgh", "12345678", "        "}) {
            String local = randomLocalPhone();
            register("A", randomUsername(), local, "a@example.com", weak)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.field").value("password"));
            assertThat(db.sql("SELECT count(*) FROM iam.user_account WHERE phone_e164 = :phone")
                    .param("phone", e164(local)).query(Integer.class).single()).as(weak).isZero();
            assertThat(sms.lastCode).doesNotContainKey(e164(local));
        }
    }

    @Test
    void aFailedSmsDoesNotUndoTheRegistrationAndIsRetriedByTheOutbox() throws Exception {
        sms.failing.set(true);
        String local = randomLocalPhone();
        JsonNode body = register("A", randomUsername(), local, "a@example.com", "Passw0rd1")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().transform(this::read);
        UUID challengeId = UUID.fromString(body.path("data").path("otpChallengeId").asText());

        assertThat(db.sql("SELECT count(*) FROM iam.user_account WHERE phone_e164 = :phone")
                .param("phone", e164(local)).query(Integer.class).single()).isOne();
        Map<String, Object> event = otpEvent(challengeId);
        assertThat(event.get("state")).isEqualTo("PENDING");                    // arch 08 section 4.3: retried
        assertThat(((Number) event.get("attempt")).intValue()).isOne();
        assertThat((String) event.get("last_error")).contains("SmsDeliveryException").doesNotContain(e164(local));
    }

    @Test
    void theOtpEventCarriesTheCodeSealedNeverInPlainTextNorThePhone() throws Exception {
        Account a = registered();
        Map<String, Object> event = otpEvent(a.challengeId());

        assertThat(event.get("state")).isEqualTo("DISPATCHED");
        assertThat(event.get("aggregate_type")).isEqualTo("OTP_CHALLENGE");
        String payload = (String) event.get("payload");
        JsonNode fields = read(payload);
        assertThat(fields.path("challengeId").asText()).isEqualTo(a.challengeId().toString());
        assertThat(payload).doesNotContain(a.code()).doesNotContain(a.phone()).doesNotContain(a.phone().substring(1));
        assertThat(otpCipher.open(a.challengeId(), fields.path("sealedCode").asText())).isEqualTo(a.code());
    }

    @Test
    void aCodeNoLongerOpenWhenItsEventIsDeliveredIsNotSent() {
        String phone = e164(randomLocalPhone());
        UUID user = insertUnverifiedUser(phone);
        UUID expired = insertChallenge(user, phone, "6 minutes", "111111");
        String otherPhone = e164(randomLocalPhone());
        UUID otherUser = insertUnverifiedUser(otherPhone);
        UUID replaced = insertChallenge(otherUser, otherPhone, "2 minutes", "222222");
        insertChallenge(otherUser, otherPhone, "0 seconds", "333333");          // supersedes the one before
        PhoneOtpSms sender = new PhoneOtpSms(otpCipher, sms, appDataSource);

        sender.handle(otpEvent(expired, "111111"));
        sender.handle(otpEvent(replaced, "222222"));

        assertThat(sms.lastCode).doesNotContainKeys(phone, otherPhone);
    }

    // ------------------------------------------------------------------------------------------------ verification

    @Test
    void theRightCodeVerifiesThePhone() throws Exception {
        Account a = registered();

        JsonNode body = verify(a.challengeId(), a.code()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString().transform(this::read);

        // E7-14, arch 09 section 2: verification answers 200 {accessToken, refreshToken} - a customer session.
        String accessToken = body.path("data").path("accessToken").asText();
        String refreshToken = body.path("data").path("refreshToken").asText();
        assertThat(body.path("meta").path("correlationId").asText()).isNotEmpty();
        JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]));
        assertThat(claims.path("sub").asText()).isEqualTo(a.userId().toString());
        assertThat(claims.path("typ").asText()).isEqualTo("CUSTOMER");
        assertThat(db.sql("SELECT subject_id FROM iam.auth_refresh_token WHERE token_hash = :hash AND revoked_at IS NULL")
                .param("hash", Sha256.hex(refreshToken)).query(UUID.class).single()).isEqualTo(a.userId());

        Map<String, Object> user = db.sql(
                        "SELECT phone_verified, phone_verified_at, email_verified FROM iam.user_account WHERE id = :id")
                .param("id", a.userId()).query().singleRow();
        assertThat(user.get("phone_verified")).isEqualTo(true);
        assertThat(user.get("phone_verified_at")).isNotNull();
        assertThat(user.get("email_verified")).as("email never activates").isEqualTo(false);
        assertThat(db.sql("SELECT consumed_at IS NOT NULL FROM iam.otp_challenge WHERE id = :id")
                .param("id", a.challengeId()).query(Boolean.class).single()).isTrue();
    }

    @Test
    void aFailedVerificationStartsNoSession() throws Exception {
        Account a = registered();

        verify(a.challengeId(), wrong(a.code()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(db.sql("SELECT count(*) FROM iam.auth_refresh_token WHERE subject_id = :id")
                .param("id", a.userId()).query(Integer.class).single()).isZero();
    }

    @Test
    void aWrongCodeIsInvalidAndItsAttemptIsKept() throws Exception {
        Account a = registered();

        verify(a.challengeId(), wrong(a.code()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("OTP_INVALID"))
                .andExpect(jsonPath("$.error.field").value("code"));

        assertThat(attempts(a.challengeId())).as("the failed attempt committed").isEqualTo(1);
    }

    @Test
    void afterFiveAttemptsEvenTheRightCodeIsRefused() throws Exception {
        Account a = registered();
        for (int i = 0; i < 5; i++) {
            verify(a.challengeId(), wrong(a.code())).andExpect(jsonPath("$.error.code").value("OTP_INVALID"));
        }

        verify(a.challengeId(), a.code())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("OTP_ATTEMPTS_EXCEEDED"));
        assertThat(attempts(a.challengeId())).isEqualTo(5);
        assertThat(phoneVerified(a.userId())).isFalse();
    }

    @Test
    void aCodeOlderThanFiveMinutesHasExpired() throws Exception {
        String phone = e164(randomLocalPhone());
        UUID userId = insertUnverifiedUser(phone);
        UUID challengeId = insertChallenge(userId, phone, "6 minutes", "123456");

        verify(challengeId, "123456")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("OTP_EXPIRED"));
        assertThat(phoneVerified(userId)).isFalse();
    }

    @Test
    void aUsedCodeCannotBeUsedAgain() throws Exception {
        Account a = registered();
        verify(a.challengeId(), a.code()).andExpect(status().isOk());

        verify(a.challengeId(), a.code())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("OTP_INVALID"));
    }

    @Test
    void aNewCodeInvalidatesThePreviousOne() throws Exception {
        String phone = e164(randomLocalPhone());
        UUID userId = insertUnverifiedUser(phone);
        UUID old = insertChallenge(userId, phone, "2 minutes", "111111");

        UUID fresh = sendOtp(phone).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()
                .transform(this::read).path("data").path("otpChallengeId").asText().transform(UUID::fromString);

        verify(old, "111111")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("OTP_INVALID"));
        verify(fresh, sms.lastCode.get(phone)).andExpect(status().isOk());
    }

    @Test
    void anUnknownChallengeIsInvalid() throws Exception {
        verify(UUID.randomUUID(), "123456")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("OTP_INVALID"));
    }

    @Test
    void aMalformedCodeIsAValidationFailureAndCostsNoAttempt() throws Exception {
        Account a = registered();

        verify(a.challengeId(), "12ab").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertThat(attempts(a.challengeId())).isZero();
    }

    // ------------------------------------------------------------------------------------------------ sending

    @Test
    void aResendInsideSixtySecondsIsRefused() throws Exception {
        Account a = registered();

        sendOtp(a.phone())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("OTP_RESEND_TOO_SOON"));
    }

    @Test
    void aSixthSendInAnHourIsRefused() throws Exception {
        String phone = e164(randomLocalPhone());
        UUID userId = insertUnverifiedUser(phone);
        for (int minutesAgo = 50; minutesAgo >= 10; minutesAgo -= 10) {
            insertChallenge(userId, phone, minutesAgo + " minutes", "000000");
        }

        sendOtp(phone)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("OTP_HOURLY_LIMIT_REACHED"));
    }

    @Test
    void thereIsNoCodeToSendForAnUnknownOrAlreadyVerifiedPhone() throws Exception {
        sendOtp(randomLocalPhone())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        Account a = registered();
        verify(a.challengeId(), a.code()).andExpect(status().isOk());
        sendOtp(a.phone())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ------------------------------------------------------------------------------------------------ security

    @Test
    void otherApiRoutesStillNeedAToken() throws Exception {
        mvc.perform(post("/api/v1/venue/owner-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current\":\"a\",\"next\":\"b\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    record Account(UUID userId, UUID challengeId, String phone, String code) {
    }

    private Account registered() throws Exception {
        String local = randomLocalPhone();
        JsonNode body = register("Test", randomUsername(), local, "t@example.com", "Passw0rd1")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().transform(this::read);
        String phone = e164(local);
        return new Account(UUID.fromString(body.path("data").path("userId").asText()),
                UUID.fromString(body.path("data").path("otpChallengeId").asText()), phone, sms.lastCode.get(phone));
    }

    private ResultActions register(String fullName, String username, String phone, String email, String password)
            throws Exception {
        ResultActions result = postJson(AUTH + "/register", Map.of("fullName", fullName, "username", username,
                "phone", phone, "email", email, "password", password));
        deliverSms();
        return result;
    }

    private ResultActions sendOtp(String phone) throws Exception {
        ResultActions result = postJson(AUTH + "/otp/send", Map.of("phone", phone));
        deliverSms();
        return result;
    }

    /** What the worker's outbox dispatcher does right after the commit (E7-41). */
    private void deliverSms() {
        OutboxDrain.drain(appDataSource, json, new PhoneOtpSms(otpCipher, sms, appDataSource));
    }

    /** The event a registration would have written for this challenge, as the dispatcher hands it over. */
    private OutboxEvent otpEvent(UUID challengeId, String code) {
        return new OutboxEvent(1, "PhoneOtpIssued", "OTP_CHALLENGE", challengeId,
                json.valueToTree(Map.of("challengeId", challengeId.toString(),
                        "sealedCode", otpCipher.seal(challengeId, code))),
                UUID.randomUUID(), Instant.EPOCH, 0);
    }

    private Map<String, Object> otpEvent(UUID challengeId) {
        return db.sql("""
                        SELECT state, attempt, last_error, aggregate_type, payload::text AS payload
                          FROM technical.outbox_event
                         WHERE event_type = 'PhoneOtpIssued' AND aggregate_id = :id""")
                .param("id", challengeId).query().singleRow();
    }

    private ResultActions verify(UUID challengeId, String code) throws Exception {
        return postJson(AUTH + "/verify-phone", Map.of("otpChallengeId", challengeId.toString(), "code", code));
    }

    private ResultActions postJson(String path, Map<String, String> body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    /** A user created directly, without the registration OTP, so a test controls the challenge times. */
    private UUID insertUnverifiedUser(String phoneE164) {
        UUID id = UUID.randomUUID();
        db.sql("""
                        INSERT INTO iam.user_account (id, full_name, username, phone_e164, email, password_hash,
                                                      phone_verified, email_verified, referral_code, joined_at)
                        VALUES (:id, 'Test', :username, :phone, 't@example.com', 'x', false, false, :referral, now())""")
                .param("id", id).param("username", randomUsername()).param("phone", phoneE164)
                .param("referral", ReferralCodes.newCode()).update();
        return id;
    }

    /** A challenge issued in the past (the database accepts a past issue time, never a future one). */
    private UUID insertChallenge(UUID userId, String phoneE164, String ago, String code) {
        UUID id = UUID.randomUUID();
        db.sql("""
                        INSERT INTO iam.otp_challenge (id, user_id, purpose, destination, code_hash, issued_at, expires_at)
                        VALUES (:id, :user, 'PHONE_VERIFICATION', :phone, :hash,
                                now() - CAST(:ago AS interval), now() - CAST(:ago AS interval) + interval '5 minutes')""")
                .param("id", id).param("user", userId).param("phone", phoneE164).param("hash", OtpCodes.hash(code))
                .param("ago", ago).update();
        return id;
    }

    private int attempts(UUID challengeId) {
        return db.sql("SELECT attempts FROM iam.otp_challenge WHERE id = :id")
                .param("id", challengeId).query(Integer.class).single();
    }

    private boolean phoneVerified(UUID userId) {
        return db.sql("SELECT phone_verified FROM iam.user_account WHERE id = :id")
                .param("id", userId).query(Boolean.class).single();
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String randomLocalPhone() {
        return "010" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    static String e164(String localPhone) {
        return PhoneNumbers.toE164(localPhone).orElseThrow();
    }

    static String randomUsername() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }
}
