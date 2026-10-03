package ps.e7gz.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.venue.VenueAccountFixtures.OWNER;
import static ps.e7gz.venue.VenueAccountFixtures.auditCount;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.audit.AuditFixtures;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-24, contract from E7-22: the Platform Admin creates the venue's one account with its first Owner Password
 * (FR-ADM-02, FR-VEN-01). Both passwords are hashed with Argon2id, never returned or echoed; the account can log in
 * and its Owner Password authorises protected actions.
 */
@AutoConfigureMockMvc
class AdminVenueAccountHttpTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    PasswordEncoder passwords;

    private String adminToken;
    private UUID venue;

    @BeforeEach
    void anAdminAndAVenueWithoutAccount() {
        adminToken = accessTokens.issue(AuthSubjectType.ADMIN, AuditFixtures.admin());
        VenueAccountFixtures.VenueWithAccount existing = VenueAccountFixtures.venueWithAccount(passwords, "1357");
        venue = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
                        VALUES (:id, :area, :name, '1 St', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING',
                                'Africa/Cairo', '+20223456789')""")
                .param("id", venue).param("area", existing.areaId()).param("name", "New " + venue).update();
    }

    @Test
    void theAccountIsCreatedEnabledWithHashedPasswordsAndEnglishByDefault() throws Exception {
        String login = "levelup." + venue;
        String body = create(venue, login, "Login-pass-1", "1234", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.venueId").value(venue.toString()))
                .andExpect(jsonPath("$.data.loginIdentifier").value(login))
                .andExpect(jsonPath("$.data.accountStatus").value("ENABLED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Login-pass-1").doesNotContain("initialOwnerPassword").doesNotContain("argon2");

        UUID account = UUID.fromString(json.readTree(body).path("data").path("id").asText());
        Map<String, Object> row = OWNER.sql("""
                        SELECT a.password_hash, a.language_preference, s.owner_password_hash, s.version
                          FROM iam.venue_account a JOIN iam.venue_owner_secret s ON s.venue_account_id = a.id
                         WHERE a.id = :id""").param("id", account).query().singleRow();
        assertThat((String) row.get("password_hash")).startsWith("$argon2id$");
        assertThat((String) row.get("owner_password_hash")).startsWith("$argon2id$");
        assertThat(passwords.matches("Login-pass-1", (String) row.get("password_hash"))).isTrue();
        assertThat(passwords.matches("1234", (String) row.get("owner_password_hash"))).isTrue();
        assertThat(row.get("language_preference")).isEqualTo("en");
        assertThat(row.get("version")).isEqualTo(0L);
        assertThat(auditCount("VENUE_ACCOUNT_CREATED", account)).isEqualTo(1);

        mvc.perform(get("/api/v1/admin/venues/" + venue).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.data.venueAccount.id").value(account.toString()));
    }

    @Test
    void theNewAccountCanLogInAndItsOwnerPasswordWorks() throws Exception {
        String login = "Mixed.Case." + venue;
        create(venue, login, "Login-pass-1", "1234", "ar").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.loginIdentifier").value(login));

        mvc.perform(post("/api/v1/venue/auth/login").header("Origin", DASHBOARD_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginIdentifier", "  " + login.toLowerCase() + " ",
                                "password", "Login-pass-1"))))
                .andExpect(status().isOk());

        UUID account = OWNER.sql("SELECT id FROM iam.venue_account WHERE venue_id = :venue").param("venue", venue)
                .query(UUID.class).single();
        changePin(accessTokens.issue(AuthSubjectType.VENUE, account), "1234").andExpect(status().isNoContent());
    }

    @Test
    void aSecondAccountForTheVenueOrATakenLoginIsRefusedWithItsCode() throws Exception {
        create(venue, "first." + venue, "Login-pass-1", "1234", "en").andExpect(status().isCreated());
        create(venue, "second." + venue, "Login-pass-1", "1234", "en")
                .andExpect(status().is(422)).andExpect(jsonPath("$.error.code").value("VENUE_ACCOUNT_ALREADY_EXISTS"));

        UUID another = VenueAccountFixtures.venueWithAccount(passwords, "1357").venueId();
        String takenLogin = OWNER.sql("SELECT login_identifier FROM iam.venue_account WHERE venue_id = :venue")
                .param("venue", another).query(String.class).single();
        UUID third = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
                        SELECT :id, area_id, 'Third ' || :id, '1 St', location, 'ONBOARDING', 'Africa/Cairo', phone
                          FROM venue.venue WHERE id = :venue""")
                .param("id", third).param("venue", venue).update();
        create(third, " " + takenLogin.toUpperCase() + " ", "Login-pass-1", "1234", "en")
                .andExpect(status().is(422)).andExpect(jsonPath("$.error.code").value("VENUE_LOGIN_IDENTIFIER_TAKEN"));
    }

    @Test
    void anUnknownVenueIsNotFoundAndMissingFieldsAreValidationFailed() throws Exception {
        create(UUID.randomUUID(), "x." + venue, "Login-pass-1", "1234", "en")
                .andExpect(status().isNotFound());
        create(venue, "x." + venue, "Login-pass-1", " ", "en")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("initialOwnerPassword"));
        create(venue, " ", "Login-pass-1", "1234", "en")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("loginIdentifier"));
        assertThat(OWNER.sql("SELECT count(*) FROM iam.venue_account WHERE venue_id = :venue").param("venue", venue)
                .query(Long.class).single()).isZero();
    }

    @Test
    void venuePasswordsFollowThePasswordRule() throws Exception {
        create(venue, "weak." + venue, "short1", "1234", "en")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("password"));
        create(venue, "weak." + venue, "Login-pass-1", "no-digits-here", "en")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("initialOwnerPassword"));
        create(venue, "weak." + venue, "Login-pass-1", "12345678", "en")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("initialOwnerPassword"));
        create(venue, "weak." + venue, "Login-pass-1", "123", "en")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("initialOwnerPassword"));
        create(venue, "weak." + venue, "Login-pass-1", "12a4", "en")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("initialOwnerPassword"));
        create(venue, "weak." + venue, "Login-pass-1", "1234", "en").andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------------------------------ FR-ADM-03

    @Test
    void disableBlocksTheDashboardAtOnceAndEnableRestoresIt() throws Exception {
        UUID account = createdAccount();
        String venueToken = accessTokens.issue(AuthSubjectType.VENUE, account);
        mvc.perform(get("/api/v1/venue/settings").header("Authorization", "Bearer " + venueToken))
                .andExpect(status().isOk());

        admin(post(ACCOUNTS + account + "/disable"), Map.of("reason", "Owner request"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accountStatus").value("DISABLED"));
        mvc.perform(get("/api/v1/venue/settings").header("Authorization", "Bearer " + venueToken))
                .andExpect(status().isUnauthorized());
        login("Login-pass-1").andExpect(status().is4xxClientError());
        assertThat(OWNER.sql("SELECT marketplace_status FROM venue.venue WHERE id = :id").param("id", venue)
                .query(String.class).single()).isEqualTo("ONBOARDING");
        assertThat(OWNER.sql("""
                        SELECT reason FROM audit.audit_event
                         WHERE action = 'VENUE_ACCOUNT_DISABLED' AND subject_id = :account""")
                .param("account", account).query(String.class).single()).isEqualTo("Owner request");

        admin(post(ACCOUNTS + account + "/disable"), Map.of("reason", "Again"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accountStatus").value("DISABLED"));
        assertThat(auditCount("VENUE_ACCOUNT_DISABLED", account)).isEqualTo(1);

        admin(post(ACCOUNTS + account + "/enable"), Map.of("reason", "Resolved"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accountStatus").value("ENABLED"));
        mvc.perform(get("/api/v1/venue/settings").header("Authorization", "Bearer " + venueToken))
                .andExpect(status().isOk());
        login("Login-pass-1").andExpect(status().isOk());
        assertThat(auditCount("VENUE_ACCOUNT_ENABLED", account)).isEqualTo(1);
    }

    @Test
    void disableAndEnableNeedAReason() throws Exception {
        UUID account = createdAccount();
        admin(post(ACCOUNTS + account + "/disable"), Map.of("reason", "  "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("reason"));
        assertThat(OWNER.sql("SELECT account_status FROM iam.venue_account WHERE id = :id").param("id", account)
                .query(String.class).single()).isEqualTo("ENABLED");
    }

    @Test
    void resetLoginPasswordReplacesItAndKeepsTheVenueData() throws Exception {
        UUID account = createdAccount();
        admin(post(ACCOUNTS + account + "/reset-login-password"), Map.of("newPassword", "weak"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("newPassword"));

        admin(post(ACCOUNTS + account + "/reset-login-password"), Map.of("newPassword", "Fresh-login-9"))
                .andExpect(status().isNoContent());
        login("Login-pass-1").andExpect(status().is4xxClientError());
        login("Fresh-login-9").andExpect(status().isOk());
        assertThat(auditCount("VENUE_LOGIN_PASSWORD_RESET", account)).isEqualTo(1);
        assertThat(OWNER.sql("SELECT count(*) FROM venue.venue WHERE id = :id").param("id", venue)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void resetOwnerPasswordReplacesItAndTheOldOneStopsWorkingAtOnce() throws Exception {
        UUID account = createdAccount();
        admin(post(ACCOUNTS + account + "/reset-owner-password"), Map.of("newPassword", "Fresh-login-9"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("newPassword"));

        admin(post(ACCOUNTS + account + "/reset-owner-password"), Map.of("newPassword", "9753"))
                .andExpect(status().isNoContent());
        assertThat(auditCount("OWNER_PASSWORD_RESET", account)).isEqualTo(1);
        assertThat(OWNER.sql("SELECT version FROM iam.venue_owner_secret WHERE venue_account_id = :id")
                .param("id", account).query(Long.class).single()).isEqualTo(1L);

        String venueToken = accessTokens.issue(AuthSubjectType.VENUE, account);
        changePin(venueToken, "1234").andExpect(status().is(423));
        changePin(venueToken, "9753").andExpect(status().isNoContent());
    }

    @Test
    void anUnknownVenueAccountIsNotFound() throws Exception {
        UUID unknown = UUID.randomUUID();
        admin(post(ACCOUNTS + unknown + "/disable"), Map.of("reason", "x")).andExpect(status().isNotFound());
        admin(post(ACCOUNTS + unknown + "/reset-owner-password"), Map.of("newPassword", "9753"))
                .andExpect(status().isNotFound());
    }

    private static final String ACCOUNTS = "/api/v1/admin/venue-accounts/";

    private UUID createdAccount() throws Exception {
        String body = create(venue, "acct." + venue, "Login-pass-1", "1234", "en")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("data").path("id").asText());
    }

    private ResultActions admin(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions login(String password) throws Exception {
        return mvc.perform(post("/api/v1/venue/auth/login").header("Origin", DASHBOARD_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("loginIdentifier", "acct." + venue, "password", password))));
    }

    /** The venue's Owner PIN change, a protected action: it shows whether {@code currentPin} is the right one. */
    private ResultActions changePin(String venueToken, String currentPin) throws Exception {
        return mvc.perform(post("/api/v1/venue/owner-password")
                .header("Authorization", "Bearer " + venueToken).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("current", currentPin, "next", "2468"))));
    }

    private ResultActions create(UUID venueId, String login, String password, String ownerPassword, String language)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("loginIdentifier", login);
        body.put("password", password);
        body.put("initialOwnerPassword", ownerPassword);
        if (language != null) {
            body.put("languagePreference", language);
        }
        return mvc.perform(post("/api/v1/admin/venues/" + venueId + "/venue-account")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
}
