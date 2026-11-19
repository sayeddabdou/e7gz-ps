package ps.e7gz.openapi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * E7-77 — every Sprint 5 stub is reachable through the real security chain and answers 501 until its task implements
 * it (clients build against {@code pnpm mock} meanwhile). Venue routes need a venue token, admin routes an admin
 * token; the wrong audience is refused before the stub, and so is a request missing its required Idempotency-Key or
 * body field. A test fails here once its task fills the stub: that task replaces the line with its own tests.
 */
@AutoConfigureMockMvc
class Sprint5StubHttpTest extends IntegrationTest {

    private static final String MAINTENANCE = "/api/v1/venue/maintenance";
    private static final String CLOSURES = "/api/v1/venue/closures";
    private static final String EMERGENCIES = "/api/v1/venue/emergencies";
    private static final String EMERGENCY = EMERGENCIES + "/" + UUID.randomUUID();
    private static final String START = "2030-01-10T10:00:00Z";
    private static final String END = "2030-01-10T12:00:00Z";
    private static final String CLOSURE = "{\"kind\":\"PARTIAL\",\"startAt\":\"" + START + "\",\"endAt\":\"" + END
            + "\",\"reason\":\"Private event\"}";

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    private String venue;
    private String admin;

    @BeforeEach
    void signIn() {
        venue = bearer(AuthSubjectType.VENUE, Fixtures.aVenueAccount().create().id());
        admin = bearer(AuthSubjectType.ADMIN, Fixtures.anAdmin().create().id());
    }

    @Test
    void maintenanceStubsAnswerNotImplemented() throws Exception {
        String window = "{\"resourceId\":\"" + UUID.randomUUID() + "\",\"startAt\":\"" + START + "\",\"endAt\":\""
                + END + "\",\"reason\":\"Controller replacement\"}";
        notImplemented(get(MAINTENANCE), venue);                                                    // E7-81
        notImplemented(json(post(MAINTENANCE), window), venue);                                     // E7-81
        notImplemented(json(patch(MAINTENANCE + "/" + UUID.randomUUID()), "{\"reason\":\"Longer\"}"), venue); // E7-81
        notImplemented(delete(MAINTENANCE + "/" + UUID.randomUUID()), venue);                       // E7-81
    }

    @Test
    void closureStubsAnswerNotImplemented() throws Exception {
        String save = "{\"proposal\":" + CLOSURE + ",\"previewToken\":\"b64:x\"}";
        notImplemented(get(CLOSURES), venue);                                                       // E7-81
        notImplemented(json(post(CLOSURES + "/preview"), "{\"proposal\":" + CLOSURE + "}"), venue);  // E7-81
        notImplemented(write(post(CLOSURES), save), venue);                                         // E7-81
        notImplemented(write(patch(CLOSURES + "/" + UUID.randomUUID()), save), venue);              // E7-81
        notImplemented(delete(CLOSURES + "/" + UUID.randomUUID()), venue);                          // E7-81
    }

    @Test
    void emergencyStubsAnswerNotImplemented() throws Exception {
        notImplemented(get(EMERGENCIES), venue);                                                    // E7-82
        notImplemented(write(post(EMERGENCIES + "/venue-closure"),
                "{\"reason\":\"Power cut\",\"expectedEndAt\":\"" + END + "\"}"), venue);            // E7-82
        notImplemented(write(post(EMERGENCIES + "/resource-outage"), "{\"resourceId\":\"" + UUID.randomUUID()
                + "\",\"reason\":\"Overheating\",\"expectedReturnAt\":\"" + END + "\"}"), venue);    // E7-82
        notImplemented(json(post(EMERGENCY + "/extend/preview"), "{\"expectedEndAt\":\"" + END + "\"}"), venue);
        notImplemented(write(post(EMERGENCY + "/extend"),
                "{\"expectedEndAt\":\"" + END + "\",\"previewToken\":\"b64:x\"}"), venue);          // E7-82
        notImplemented(write(post(EMERGENCY + "/end"), "{}"), venue);                                // E7-82, E7-84
        String conversion = "{\"target\":\"MAINTENANCE\",\"maintenance\":{\"endAt\":\"" + END
                + "\",\"reason\":\"Part on order\"}}";
        notImplemented(json(post(EMERGENCY + "/convert/preview"), conversion), venue);              // E7-82
        notImplemented(write(post(EMERGENCY + "/convert"),
                "{\"conversion\":" + conversion + ",\"previewToken\":\"b64:x\"}"), venue);           // E7-82
    }

    @Test
    void ownerReviewStubsAnswerNotImplemented() throws Exception {
        notImplemented(get("/api/v1/venue/owner-reviews"), venue);                                  // E7-84
        notImplemented(json(post("/api/v1/venue/owner-reviews/" + UUID.randomUUID() + "/resolve"),
                "{\"action\":\"RETURN_TO_SERVICE\",\"ownerPassword\":\"1234\"}"), venue);            // E7-84
    }

    @Test
    void adminStubsAnswerNotImplemented() throws Exception {
        notImplemented(get("/api/v1/admin/venue-accounts"), admin);                                 // E7-78
        notImplemented(get("/api/v1/admin/venue-accounts").param("accountStatus", "DISABLED"), admin);
        notImplemented(get("/api/v1/admin/owner-reviews"), admin);                                  // E7-84
        notImplemented(get("/api/v1/admin/emergencies").param("status", "ACTIVE"), admin);          // E7-82
    }

    @Test
    void anHoursCommitThatConvertsAnEmergencyAnswersNotImplemented() throws Exception {
        // PO decision D5 (19 Nov 2026): the emergencyId part waits for E7-82; an ordinary commit is unchanged.
        String body = "{\"proposal\":{\"defaultOpen\":\"12:00:00\",\"defaultClose\":\"23:00:00\","
                + "\"defaultFullDay\":false,\"days\":[]},\"previewToken\":\"b64:x\",\"emergencyId\":\""
                + UUID.randomUUID() + "\"}";
        notImplemented(write(post("/api/v1/venue/opening-hours/commit"), body), venue);
    }

    @Test
    void eachRouteGroupRefusesTheOtherAudience() throws Exception {
        // arch 09 §1.1: a venue token never reaches an admin route, and the other way round.
        mvc.perform(get("/api/v1/admin/emergencies").header("Authorization", venue))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get(EMERGENCIES).header("Authorization", admin))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get(CLOSURES)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void theCascadingWritesRefuseAMissingIdempotencyKeyOrReason() throws Exception {
        // Validation runs before the stub: the contract's required key and fields are real (E7-85 relies on them).
        mvc.perform(json(post(EMERGENCIES + "/venue-closure"), "{\"reason\":\"Power cut\",\"expectedEndAt\":\""
                        + END + "\"}").header("Authorization", venue))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(write(post(EMERGENCIES + "/venue-closure"), "{\"reason\":\" \",\"expectedEndAt\":\"" + END
                        + "\"}").header("Authorization", venue))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("reason"));
        mvc.perform(write(post(CLOSURES), "{\"proposal\":" + CLOSURE + "}").header("Authorization", venue))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("previewToken"));
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
