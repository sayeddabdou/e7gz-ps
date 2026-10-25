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

    /** E7-114 built cancel-confirmed (VenueCancelConfirmedHttpTest); the Overview waits for E7-115. */
    @Test
    void overviewIsAStub() throws Exception {
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

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
