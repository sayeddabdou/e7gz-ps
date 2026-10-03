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
        VenueAccountFixtures.VenueWithAccount existing = VenueAccountFixtures.venueWithAccount(passwords, "x-pass-1");
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
        String body = create(venue, login, "Login-pass-1", "Owner-pass-1", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.venueId").value(venue.toString()))
                .andExpect(jsonPath("$.data.loginIdentifier").value(login))
                .andExpect(jsonPath("$.data.accountStatus").value("ENABLED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Login-pass-1").doesNotContain("Owner-pass-1").doesNotContain("argon2");

        UUID account = UUID.fromString(json.readTree(body).path("data").path("id").asText());
        Map<String, Object> row = OWNER.sql("""
                        SELECT a.password_hash, a.language_preference, s.owner_password_hash, s.version
                          FROM iam.venue_account a JOIN iam.venue_owner_secret s ON s.venue_account_id = a.id
                         WHERE a.id = :id""").param("id", account).query().singleRow();
        assertThat((String) row.get("password_hash")).startsWith("$argon2id$");
        assertThat((String) row.get("owner_password_hash")).startsWith("$argon2id$");
        assertThat(passwords.matches("Login-pass-1", (String) row.get("password_hash"))).isTrue();
        assertThat(passwords.matches("Owner-pass-1", (String) row.get("owner_password_hash"))).isTrue();
        assertThat(row.get("language_preference")).isEqualTo("en");
        assertThat(row.get("version")).isEqualTo(0L);
        assertThat(auditCount("VENUE_ACCOUNT_CREATED", account)).isEqualTo(1);

        mvc.perform(get("/api/v1/admin/venues/" + venue).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.data.venueAccount.id").value(account.toString()));
    }

    @Test
    void theNewAccountCanLogInAndItsOwnerPasswordWorks() throws Exception {
        String login = "Mixed.Case." + venue;
        create(venue, login, "Login-pass-1", "Owner-pass-1", "ar").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.loginIdentifier").value(login));

        mvc.perform(post("/api/v1/venue/auth/login").header("Origin", DASHBOARD_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginIdentifier", "  " + login.toLowerCase() + " ",
                                "password", "Login-pass-1"))))
                .andExpect(status().isOk());

        UUID account = OWNER.sql("SELECT id FROM iam.venue_account WHERE venue_id = :venue").param("venue", venue)
                .query(UUID.class).single();
        UUID photo = VenueAccountFixtures.visiblePhoto(venue, 1);
        mvc.perform(post("/api/v1/venue/images/" + photo + "/remove")
                        .header("Authorization", "Bearer " + accessTokens.issue(AuthSubjectType.VENUE, account))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("ownerPassword", "Owner-pass-1"))))
                .andExpect(status().isNoContent());
    }

    @Test
    void aSecondAccountForTheVenueOrATakenLoginIsRefusedWithItsCode() throws Exception {
        create(venue, "first." + venue, "Login-pass-1", "Owner-pass-1", "en").andExpect(status().isCreated());
        create(venue, "second." + venue, "Login-pass-1", "Owner-pass-1", "en")
                .andExpect(status().is(422)).andExpect(jsonPath("$.error.code").value("VENUE_ACCOUNT_ALREADY_EXISTS"));

        UUID another = VenueAccountFixtures.venueWithAccount(passwords, "x-pass-1").venueId();
        String takenLogin = OWNER.sql("SELECT login_identifier FROM iam.venue_account WHERE venue_id = :venue")
                .param("venue", another).query(String.class).single();
        UUID third = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
                        SELECT :id, area_id, 'Third ' || :id, '1 St', location, 'ONBOARDING', 'Africa/Cairo', phone
                          FROM venue.venue WHERE id = :venue""")
                .param("id", third).param("venue", venue).update();
        create(third, " " + takenLogin.toUpperCase() + " ", "Login-pass-1", "Owner-pass-1", "en")
                .andExpect(status().is(422)).andExpect(jsonPath("$.error.code").value("VENUE_LOGIN_IDENTIFIER_TAKEN"));
    }

    @Test
    void anUnknownVenueIsNotFoundAndMissingFieldsAreValidationFailed() throws Exception {
        create(UUID.randomUUID(), "x." + venue, "Login-pass-1", "Owner-pass-1", "en")
                .andExpect(status().isNotFound());
        create(venue, "x." + venue, "Login-pass-1", " ", "en")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("initialOwnerPassword"));
        create(venue, " ", "Login-pass-1", "Owner-pass-1", "en")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("loginIdentifier"));
        assertThat(OWNER.sql("SELECT count(*) FROM iam.venue_account WHERE venue_id = :venue").param("venue", venue)
                .query(Long.class).single()).isZero();
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
