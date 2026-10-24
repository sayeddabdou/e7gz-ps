package ps.e7gz.openapi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-65 — every Sprint 4 stub is reachable through the real security chain and answers 501 until its task implements
 * it (clients build against {@code pnpm mock} meanwhile). Customer routes need a customer token, admin routes an admin
 * token; the wrong audience is refused before the stub. A test fails here once its task fills the stub: that task
 * replaces the line with its own tests.
 */
@AutoConfigureMockMvc
class Sprint4StubHttpTest extends IntegrationTest {

    private static final String USER = "/api/v1/admin/users/" + UUID.randomUUID();
    private static final String BAN = USER + "/bans/" + UUID.randomUUID();
    private static final String PLAYER = "/api/v1/admin/verified-players/" + UUID.randomUUID();
    private static final String ATTENDANCE = "/api/v1/admin/bookings/" + UUID.randomUUID() + "/attendance";
    private static final String REASON = "{\"reason\":\"Venue confirmed the customer was present.\"}";

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    private String customer;
    private String unverified;
    private String admin;

    @BeforeEach
    void signIn() {
        customer = bearer(AuthSubjectType.CUSTOMER, Fixtures.aUser().verified().create().id());
        unverified = bearer(AuthSubjectType.CUSTOMER, Fixtures.aUser().create().id());
        admin = bearer(AuthSubjectType.ADMIN, Fixtures.anAdmin().create().id());
    }

    @Test
    void customerStandingAndReferralsAreStubs() throws Exception {
        notImplemented(get("/api/v1/customer/account-standing"), customer);         // E7-71, E7-72
        notImplemented(get("/api/v1/customer/referrals"), customer);                // E7-67
        // FRS §2.11: standing and history are open to every customer state, unverified included.
        notImplemented(get("/api/v1/customer/account-standing"), unverified);
    }

    @Test
    void adminTrustAndVerifiedPlayerRoutesAreStubs() throws Exception {
        notImplemented(write(post(USER + "/trust-adjustment"),
                "{\"counterAfter\":0,\"penaltyStageAfter\":0,\"reason\":\"Wrong no-show.\"}"), admin); // E7-71
        notImplemented(write(post(BAN + "/lift"), REASON), admin);                  // E7-71
        notImplemented(write(post(BAN + "/shorten"),
                "{\"endsAt\":\"2026-11-01T10:00:00Z\",\"reason\":\"Shortened.\"}"), admin);      // E7-71
        notImplemented(write(post(BAN + "/correct"), REASON), admin);               // E7-71
        notImplemented(get("/api/v1/admin/verified-players"), admin);               // E7-72
        notImplemented(write(post(PLAYER + "/suspend"), REASON), admin);            // E7-72
        notImplemented(write(post(PLAYER + "/revoke"), REASON), admin);             // E7-72
        notImplemented(write(post(PLAYER + "/restore"), REASON), admin);            // E7-72
    }

    @Test
    void adminAttendanceRemediationRoutesAreStubs() throws Exception {
        notImplemented(write(post(ATTENDANCE + "/reverse-check-in"), REASON), admin);       // E7-70
        notImplemented(write(post(ATTENDANCE + "/reverse-late-present"), REASON), admin);   // E7-70
        notImplemented(write(post(ATTENDANCE + "/reverse-no-show"), REASON), admin);        // E7-70
        notImplemented(write(post(ATTENDANCE + "/correct"),
                "{\"correctedOutcome\":\"ATTENDED\",\"reason\":\"Present all session.\"}"), admin); // E7-70
    }

    @Test
    void adminReferralAndConfigurationRoutesAreStubs() throws Exception {
        notImplemented(get("/api/v1/admin/referrals"), admin);                      // E7-67
        notImplemented(write(post("/api/v1/admin/referrals/" + UUID.randomUUID() + "/invalidate"), REASON),
                admin);                                                              // E7-67
        notImplemented(get("/api/v1/admin/points/settings"), admin);                // E7-68
        notImplemented(write(put("/api/v1/admin/points/settings"),
                "{\"attendedPoints\":250,\"reason\":\"Autumn promotion.\"}"), admin); // E7-68
        notImplemented(get("/api/v1/admin/fees"), admin);                           // E7-68
        notImplemented(get("/api/v1/admin/global-rules"), admin);                   // E7-71 (PO 20 Oct)
        notImplemented(write(put("/api/v1/admin/fees"), "{\"bookingFee\":{\"amount\":\"5.00\",\"currency\":\"EGP\"},"
                + "\"reason\":\"Fee switched on.\"}"), admin);                      // E7-68
    }

    @Test
    void eachRouteGroupRefusesTheOtherAudience() throws Exception {
        // arch 09 §1.1: the customer token never reaches an admin route, and the other way round.
        mvc.perform(get("/api/v1/admin/points").header("Authorization", customer))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/customer/wallet").header("Authorization", admin))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/customer/wallet"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void theAdminWritesRefuseAMissingReasonOrIdempotencyKey() throws Exception {
        // Validation runs before the stub: the contract's required reason and key are real (E7-75 relies on them).
        mvc.perform(json(post(BAN + "/lift"), "{}").header("Authorization", admin)
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("reason"));
        mvc.perform(json(post(BAN + "/lift"), REASON).header("Authorization", admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private void notImplemented(MockHttpServletRequestBuilder request, String token) throws Exception {
        mvc.perform(request.header("Authorization", token)).andExpect(status().isNotImplemented());
    }

    private String bearer(AuthSubjectType type, UUID id) {
        return "Bearer " + accessTokens.issue(type, id);
    }

    private static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, String body) {
        return json(request, body).header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
