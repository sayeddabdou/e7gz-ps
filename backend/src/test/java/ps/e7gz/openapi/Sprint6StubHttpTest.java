package ps.e7gz.openapi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-88 — the Sprint 6 read routes through the real security chain: each route group refuses the other audiences, and
 * the query parameters are validated before the read. E7-126 filled all seven stubs and replaced their 501 lines with
 * its own tests ({@code ps.e7gz.tournament.internal.TournamentReadsHttpTest}). The Sprint 6 writes were built
 * directly by E7-91, E7-92 and E7-93.
 */
@AutoConfigureMockMvc
class Sprint6StubHttpTest extends IntegrationTest {

    private static final String CUSTOMER = "/api/v1/customer/tournaments";
    private static final String VENUE = "/api/v1/venue/tournaments";
    private static final String ADMIN = "/api/v1/admin/tournaments";

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    private String customer;
    private String venue;
    private String admin;

    @BeforeEach
    void signIn() {
        customer = bearer(AuthSubjectType.CUSTOMER, Fixtures.aUser().create().id());
        venue = bearer(AuthSubjectType.VENUE, Fixtures.aVenueAccount().create().id());
        admin = bearer(AuthSubjectType.ADMIN, Fixtures.anAdmin().create().id());
    }

    @Test
    void eachRouteGroupRefusesTheOtherAudiences() throws Exception {
        // arch 09 §1.1: three route groups, each with its own audience.
        mvc.perform(get(ADMIN).header("Authorization", venue))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get(VENUE).header("Authorization", customer))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get(CUSTOMER).header("Authorization", admin))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get(CUSTOMER)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void theQueryParametersAreValidatedBeforeTheRead() throws Exception {
        // The contract's limits and code enums are real: the clients rely on them (arch 09 §1.2).
        mvc.perform(get(CUSTOMER).param("limit", "0").header("Authorization", customer))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(get(VENUE).param("status", "NOT_A_STATUS").header("Authorization", venue))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(get(ADMIN).param("limit", "101").header("Authorization", admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private String bearer(AuthSubjectType type, UUID id) {
        return "Bearer " + accessTokens.issue(type, id);
    }
}
