package ps.e7gz.openapi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * E7-54 — every Sprint 3 stub is reachable through the real security chain and answers 501 until its task implements
 * it (clients build against {@code pnpm mock} meanwhile). Password reset is public (arch 09 §1.2); every other route
 * needs its own route group's token. A test fails here once its task fills the stub: that task replaces the line with
 * its own tests.
 */
@AutoConfigureMockMvc
class Sprint3StubHttpTest extends IntegrationTest {

    private static final String VENUE_BOOKING = "/api/v1/venue/bookings/" + UUID.randomUUID();
    private static final String CHALLENGE = "{\"otpChallengeId\":\"" + UUID.randomUUID() + "\",\"code\":\"123456\"}";

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    private String customer;
    private String venue;

    @BeforeEach
    void signIn() {
        customer = "Bearer " + accessTokens.issue(AuthSubjectType.CUSTOMER, Fixtures.aUser().verified().create().id());
        venue = "Bearer " + accessTokens.issue(AuthSubjectType.VENUE, Fixtures.aVenueAccount().create().id());
    }

    @Test
    void attendanceCancelConfirmedAndOverviewAreStubs() throws Exception {
        notImplemented(idempotent(post(VENUE_BOOKING + "/check-in")), venue);               // E7-59
        notImplemented(idempotent(post(VENUE_BOOKING + "/late-present")), venue);           // E7-59
        notImplemented(idempotent(post(VENUE_BOOKING + "/no-show")), venue);                // E7-59
        notImplemented(json(idempotent(post(VENUE_BOOKING + "/cancel-confirmed")), "{\"ownerPassword\":\"1234\"}"),
                venue);                                                                      // E7-114
        notImplemented(get("/api/v1/venue/overview"), venue);                               // E7-115
    }

    /** E7-57 built the profile and password reset; only the email pair waits for the PO's email decision. */
    @Test
    void emailChangeAndVerificationAreStillStubs() throws Exception {
        notImplemented(json(post("/api/v1/customer/profile/email"), "{\"email\":\"omar@example.com\"}"), customer);
        notImplemented(json(post("/api/v1/customer/profile/email/verify"), CHALLENGE), customer);
    }

    private void notImplemented(MockHttpServletRequestBuilder request, String token) throws Exception {
        if (token != null) {
            request.header("Authorization", token);
        }
        mvc.perform(request).andExpect(status().isNotImplemented());
    }

    private static MockHttpServletRequestBuilder idempotent(MockHttpServletRequestBuilder request) {
        return request.header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
