package ps.e7gz.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.venue.VenueAccountFixtures.OWNER;

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
import com.fasterxml.jackson.databind.ObjectMapper;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-24, PO decision 3 Oct 2026 (arch 09 §4.2, FR-VEN-40). Done when: GET returns the signed-in venue's profile and
 * language; PATCH changes only the fields sent; a blank name or a bad phone is VALIDATION_FAILED on that field; another
 * venue's data is never read or changed.
 */
@AutoConfigureMockMvc
class VenueSettingsHttpTest extends IntegrationTest {

    private static final String SETTINGS = "/api/v1/venue/settings";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    PasswordEncoder passwords;

    private VenueAccountFixtures.VenueWithAccount venue;
    private VenueAccountFixtures.VenueWithAccount other;
    private String token;

    @BeforeEach
    void twoVenues() {
        venue = VenueAccountFixtures.venueWithAccount(passwords, "1234");
        other = VenueAccountFixtures.venueWithAccount(passwords, "5678");
        token = accessTokens.issue(AuthSubjectType.VENUE, venue.accountId());
    }

    @Test
    void getReturnsTheSignedInVenuesProfileAndLanguage() throws Exception {
        call(get(SETTINGS), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.venueId").value(venue.venueId().toString()))
                .andExpect(jsonPath("$.data.name").value("Venue " + venue.venueId()))
                .andExpect(jsonPath("$.data.areaId").value(venue.areaId().toString()))
                .andExpect(jsonPath("$.data.areaName").value("Area " + venue.areaId()))
                .andExpect(jsonPath("$.data.address").value("12 Test St"))
                .andExpect(jsonPath("$.data.location.lat").value(30.0))
                .andExpect(jsonPath("$.data.location.lng").value(31.2))
                .andExpect(jsonPath("$.data.phone").value("+20223456789"))
                .andExpect(jsonPath("$.data.timezone").value("Africa/Cairo"))
                .andExpect(jsonPath("$.data.marketplaceStatus").value("ONBOARDING"))
                .andExpect(jsonPath("$.data.activities[0]").value("PLAYSTATION"))
                .andExpect(jsonPath("$.data.languagePreference").value("en"));
    }

    @Test
    void patchChangesOnlyTheFieldsSent() throws Exception {
        call(patch(SETTINGS), Map.of("phone", "0100 123 4567")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("+201001234567"))
                .andExpect(jsonPath("$.data.name").value("Venue " + venue.venueId()))
                .andExpect(jsonPath("$.data.address").value("12 Test St"))
                .andExpect(jsonPath("$.data.languagePreference").value("en"));

        call(patch(SETTINGS), Map.of("name", "  Level Up Lounge ", "languagePreference", "ar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Level Up Lounge"))
                .andExpect(jsonPath("$.data.phone").value("+201001234567"))
                .andExpect(jsonPath("$.data.languagePreference").value("ar"));
        assertThat(OWNER.sql("SELECT language_preference FROM iam.venue_account WHERE id = :id")
                .param("id", venue.accountId()).query(String.class).single()).isEqualTo("ar");

        call(patch(SETTINGS), Map.of("address", "1 New St")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.address").value("1 New St"))
                .andExpect(jsonPath("$.data.name").value("Level Up Lounge"));
    }

    @Test
    void adminOnlyFieldsAreNotChangedFromTheDashboard() throws Exception {
        call(patch(SETTINGS), Map.of("areaId", other.areaId(), "marketplaceStatus", "ACTIVE",
                        "timezone", "Europe/London", "activities", new String[] {"BILLIARDS"},
                        "location", Map.of("lat", 1, "lng", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.areaId").value(venue.areaId().toString()))
                .andExpect(jsonPath("$.data.marketplaceStatus").value("ONBOARDING"))
                .andExpect(jsonPath("$.data.timezone").value("Africa/Cairo"))
                .andExpect(jsonPath("$.data.activities[0]").value("PLAYSTATION"))
                .andExpect(jsonPath("$.data.location.lat").value(30.0));
    }

    @Test
    void aBlankNameOrABadPhoneIsValidationFailedOnThatField() throws Exception {
        call(patch(SETTINGS), Map.of("name", "   ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("name"));
        call(patch(SETTINGS), Map.of("phone", "12345")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("phone"));
        call(patch(SETTINGS), Map.of("address", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("address"));
        call(patch(SETTINGS), Map.of("name", "x".repeat(201))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("name"));
        call(patch(SETTINGS), Map.of("languagePreference", "fr")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        call(get(SETTINGS), null).andExpect(jsonPath("$.data.name").value("Venue " + venue.venueId()))
                .andExpect(jsonPath("$.data.phone").value("+20223456789"));
    }

    @Test
    void anotherVenuesDataIsNeverReadOrChanged() throws Exception {
        call(get(SETTINGS), null).andExpect(jsonPath("$.data.venueId").value(venue.venueId().toString()));
        call(patch(SETTINGS), Map.of("name", "Renamed", "languagePreference", "ar")).andExpect(status().isOk());

        assertThat(name(other.venueId())).isEqualTo("Venue " + other.venueId());
        assertThat(OWNER.sql("SELECT language_preference FROM iam.venue_account WHERE id = :id")
                .param("id", other.accountId()).query(String.class).single()).isEqualTo("en");
        assertThat(name(venue.venueId())).isEqualTo("Renamed");
    }

    @Test
    void noOwnerPasswordIsAskedAndNoneIsChecked() throws Exception {
        call(patch(SETTINGS), Map.of("name", "No Password Needed")).andExpect(status().isOk());
        assertThat(OWNER.sql("SELECT count(*) FROM audit.audit_event WHERE actor_id = :account")
                .param("account", venue.accountId()).query(Long.class).single()).isZero();
    }

    @Test
    void onlyAVenueMaySeeTheseRoutes() throws Exception {
        mvc.perform(get(SETTINGS)).andExpect(status().isUnauthorized());
        mvc.perform(get(SETTINGS).header("Authorization",
                "Bearer " + accessTokens.issue(AuthSubjectType.CUSTOMER, UUID.randomUUID())))
                .andExpect(status().is4xxClientError());
    }

    private String name(UUID venueId) {
        return OWNER.sql("SELECT name FROM venue.venue WHERE id = :id").param("id", venueId).query(String.class)
                .single();
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }
}
