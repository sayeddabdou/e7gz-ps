package ps.e7gz.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-78, carry-forward from E7-37 (PO decision 4 Oct 2026; FR-ADM-03): {@code GET /admin/venue-accounts} lists every
 * venue account with its venue's name, ordered by venue name then id, with cursor paging like {@code GET /admin/venues}
 * and an optional {@code accountStatus} filter - never a password, hash or Owner PIN, and only for the Platform Admin.
 */
@AutoConfigureMockMvc
class AdminVenueAccountListHttpTest extends IntegrationTest {

    private static final String ACCOUNTS = "/api/v1/admin/venue-accounts";
    private static final String LOGIN_HASH = "$argon2id$v=19$m=19456,t=2,p=1$bG9naW4tc2FsdA$login-hash-must-not-leak";
    private static final String OWNER_HASH = "$argon2id$v=19$m=19456,t=2,p=1$b3duZXItc2FsdA$owner-hash-must-not-leak";

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ObjectMapper json;

    private String admin;
    private String prefix;
    private List<Fixtures.VenueAccount> mine;

    @BeforeEach
    void threeVenuesWithAccounts() {
        admin = "Bearer " + accessTokens.issue(AuthSubjectType.ADMIN, Fixtures.anAdmin().create().id());
        // names sort together and in a known order whatever else the shared database holds
        prefix = "AAA list " + UUID.randomUUID() + " ";
        mine = new ArrayList<>();
        for (String name : List.of("Charlie", "Alpha", "Bravo")) {
            Fixtures.Venue venue = Fixtures.aVenue().named(prefix + name).create();
            Fixtures.VenueAccount account = name.equals("Bravo")
                    ? Fixtures.aVenueAccount().forVenue(venue).passwordHash(LOGIN_HASH).ownerPasswordHash(OWNER_HASH)
                            .disabled().create()
                    : Fixtures.aVenueAccount().forVenue(venue).passwordHash(LOGIN_HASH).ownerPasswordHash(OWNER_HASH)
                            .create();
            mine.add(account);
        }
    }

    /** Every venue's account, each exactly once across the pages, in venue-name order, with its venue's name. */
    @Test
    void pagingListsEveryAccountExactlyOnceInVenueNameOrder() throws Exception {
        List<JsonNode> rows = all(null, 2);

        List<String> ids = rows.stream().map(row -> row.path("id").asText()).toList();
        assertThat(ids).doesNotHaveDuplicates();
        List<String> names = rows.stream().map(row -> row.path("venueName").asText())
                .filter(name -> name.startsWith(prefix)).toList();
        assertThat(names).containsExactly(prefix + "Alpha", prefix + "Bravo", prefix + "Charlie");
        assertThat(rows).filteredOn(row -> row.path("venueName").asText().startsWith(prefix))
                .extracting(row -> row.path("id").asText())
                .containsExactlyInAnyOrder(mine.stream().map(a -> a.id().toString()).toArray(String[]::new));
        JsonNode bravo = rows.stream().filter(row -> row.path("venueName").asText().equals(prefix + "Bravo"))
                .findFirst().orElseThrow();
        assertThat(bravo.path("accountStatus").asText()).isEqualTo("DISABLED");
        assertThat(bravo.path("languagePreference").asText()).isEqualTo("en");
        assertThat(bravo.path("venueId").asText()).isEqualTo(mine.get(2).venueId().toString());
        assertThat(bravo.path("loginIdentifier").asText()).isEqualTo(mine.get(2).loginIdentifier());
    }

    @Test
    void theStatusFilterKeepsOnlyThatStatus() throws Exception {
        List<JsonNode> disabled = all("DISABLED", 100);
        List<JsonNode> enabled = all("ENABLED", 100);

        assertThat(disabled).allSatisfy(row -> assertThat(row.path("accountStatus").asText()).isEqualTo("DISABLED"));
        assertThat(enabled).allSatisfy(row -> assertThat(row.path("accountStatus").asText()).isEqualTo("ENABLED"));
        assertThat(disabled).extracting(row -> row.path("venueName").asText()).contains(prefix + "Bravo")
                .doesNotContain(prefix + "Alpha", prefix + "Charlie");
        assertThat(enabled).extracting(row -> row.path("venueName").asText())
                .contains(prefix + "Alpha", prefix + "Charlie").doesNotContain(prefix + "Bravo");
    }

    @Test
    void theAnswerNeverCarriesAPasswordHashOrOwnerPin() throws Exception {
        String body = mvc.perform(get(ACCOUNTS).param("limit", "100").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("login-hash-must-not-leak").doesNotContain("owner-hash-must-not-leak")
                .doesNotContain("argon2").doesNotContainIgnoringCase("password").doesNotContain("ownerPin");
    }

    @Test
    void venueAndCustomerTokensAreRefused() throws Exception {
        String venue = "Bearer " + accessTokens.issue(AuthSubjectType.VENUE, mine.getFirst().id());
        String customer = "Bearer " + accessTokens.issue(AuthSubjectType.CUSTOMER, Fixtures.aUser().create().id());

        mvc.perform(get(ACCOUNTS).header("Authorization", venue)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get(ACCOUNTS).header("Authorization", customer)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get(ACCOUNTS)).andExpect(status().isUnauthorized());
    }

    @Test
    void aMalformedCursorIsAValidationError() throws Exception {
        mvc.perform(get(ACCOUNTS).param("cursor", "%%%").header("Authorization", admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("cursor"));
    }

    /** Every row, following {@code nextCursor} page by page. */
    private List<JsonNode> all(String accountStatus, int limit) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        String cursor = null;
        do {
            MockHttpServletRequestBuilder request = get(ACCOUNTS).param("limit", String.valueOf(limit))
                    .header("Authorization", admin);
            if (accountStatus != null) {
                request = request.param("accountStatus", accountStatus);
            }
            if (cursor != null) {
                request = request.param("cursor", cursor);
            }
            JsonNode page = json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString()).path("data");
            page.path("items").forEach(rows::add);
            cursor = page.path("nextCursor").isNull() || page.path("nextCursor").isMissingNode() ? null
                    : page.path("nextCursor").asText();
        } while (cursor != null);
        return rows;
    }
}
