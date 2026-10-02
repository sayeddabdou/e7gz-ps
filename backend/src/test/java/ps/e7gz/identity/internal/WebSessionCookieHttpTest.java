package ps.e7gz.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.identity.internal.AuthSessionsHttpTest.refreshCookie;
import static ps.e7gz.identity.internal.AuthSessionsHttpTest.webSessionCall;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ps.e7gz.common.security.WebOrigins;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-22 (Sprint 0 review S-4) - the Venue Dashboard / Platform Admin browser session: the refresh token only in an
 * HttpOnly cookie, refresh and logout guarded by Origin + custom header (CSRF), CORS only for the configured web
 * origins; the Customer app unchanged.
 */
@AutoConfigureMockMvc
class WebSessionCookieHttpTest extends IntegrationTest {

    private static final String PASSWORD = "Passw0rd1";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    DataSource appDataSource;

    JdbcClient db;
    String passwordHash;
    String ip;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(appDataSource);
        if (passwordHash == null) {
            passwordHash = passwords.encode(PASSWORD);
        }
        ip = "10." + ThreadLocalRandom.current().nextInt(256) + "." + ThreadLocalRandom.current().nextInt(256) + "."
                + ThreadLocalRandom.current().nextInt(256);
    }

    // ------------------------------------------------------------------------------------------------ the cookie

    @Test
    void venueLoginSetsTheRefreshTokenOnlyInAStrictHttpOnlyCookie() throws Exception {
        ResultActions login = venueLogin().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist());

        String cookie = sessionCookieHeader(login);
        assertThat(cookie).startsWith("e7gz_venue_refresh=")
                .contains("; Path=/api/v1/venue/auth", "; Max-Age=604800", "; Secure", "; HttpOnly",
                        "; SameSite=Strict");
        assertThat(liveSession(refreshCookie(login))).isTrue();
    }

    @Test
    void adminLoginSetsATwelveHourCookieOnTheAdminAuthPath() throws Exception {
        ResultActions login = adminLogin().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist());

        assertThat(sessionCookieHeader(login)).startsWith("e7gz_admin_refresh=")
                .contains("; Path=/api/v1/admin/auth", "; Max-Age=43200", "; Secure", "; HttpOnly",
                        "; SameSite=Strict");
    }

    @Test
    void refreshRotatesTheCookieAndAReusedOneIsRefusedAndCleared() throws Exception {
        String a = refreshCookie(venueLogin());

        ResultActions refreshed = mvc.perform(webSessionCall("venue", "refresh", a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist());
        String b = refreshCookie(refreshed);
        assertThat(b).isNotEqualTo(a);

        ResultActions reused = mvc.perform(webSessionCall("venue", "refresh", a))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        assertThat(sessionCookieHeader(reused)).startsWith("e7gz_venue_refresh=;").contains("Max-Age=0");
        assertThat(liveSession(b)).as("reuse revoked the chain").isFalse();
    }

    @Test
    void refreshWithoutACookieIsUnauthenticatedAndABodyTokenIsIgnored() throws Exception {
        String token = refreshCookie(venueLogin());

        mvc.perform(webSessionCall("venue", "refresh", null).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", token))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        assertThat(rotatedTo(token)).isNull();
    }

    @Test
    void logoutRevokesTheSessionAndClearsTheCookie() throws Exception {
        String token = refreshCookie(adminLogin());

        ResultActions logout = mvc.perform(webSessionCall("admin", "logout", token)).andExpect(status().isNoContent());

        assertThat(sessionCookieHeader(logout)).startsWith("e7gz_admin_refresh=;").contains("Max-Age=0",
                "Path=/api/v1/admin/auth");
        assertThat(liveSession(token)).isFalse();
        mvc.perform(webSessionCall("admin", "logout", null)).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------------------------------------ CSRF

    @ParameterizedTest
    @ValueSource(strings = {"refresh", "logout"})
    void aCallWithoutTheCustomHeaderIsForbiddenAndChangesNothing(String operation) throws Exception {
        String token = refreshCookie(venueLogin());

        mvc.perform(post("/api/v1/venue/auth/" + operation).header("Origin", DASHBOARD_ORIGIN)
                        .cookie(new Cookie("e7gz_venue_refresh", token)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(post("/api/v1/venue/auth/" + operation).header("Origin", DASHBOARD_ORIGIN)
                        .header(WebOrigins.CSRF_HEADER, "XMLHttpRequest")
                        .cookie(new Cookie("e7gz_venue_refresh", token)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(liveSession(token)).isTrue();
        assertThat(rotatedTo(token)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"refresh", "logout"})
    void aCallFromAnotherOriginOrWithoutOneIsForbiddenAndChangesNothing(String operation) throws Exception {
        String token = refreshCookie(adminLogin());

        for (String origin : new String[] {"https://evil.example", "null", ADMIN_ORIGIN + "/",
                ADMIN_ORIGIN.replace("https", "http")}) {
            mvc.perform(post("/api/v1/admin/auth/" + operation).header("Origin", origin)
                            .header(WebOrigins.CSRF_HEADER, WebOrigins.CSRF_HEADER_VALUE)
                            .cookie(new Cookie("e7gz_admin_refresh", token)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        }
        mvc.perform(post("/api/v1/admin/auth/" + operation)
                        .header(WebOrigins.CSRF_HEADER, WebOrigins.CSRF_HEADER_VALUE)
                        .cookie(new Cookie("e7gz_admin_refresh", token)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(liveSession(token)).isTrue();
        assertThat(rotatedTo(token)).isNull();
    }

    // ------------------------------------------------------------------------------------------------ customer

    @Test
    void theCustomerAppKeepsTokensInTheBodyAndGetsNoCookie() throws Exception {
        TestCustomers.Customer c = TestCustomers.insert(db, true, passwordHash);

        mvc.perform(post("/api/v1/customer/auth/login").with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                }).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("usernameOrPhone", c.username(), "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    // ------------------------------------------------------------------------------------------------ CORS

    @Test
    void corsAllowsTheConfiguredWebOriginsWithCredentialsOnVenueAndAdminRoutes() throws Exception {
        for (String[] route : new String[][] {{"/api/v1/venue/auth/refresh", DASHBOARD_ORIGIN},
                {"/api/v1/admin/areas", ADMIN_ORIGIN}}) {
            mvc.perform(options(route[0]).header("Origin", route[1])
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "authorization, x-requested-with, content-type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", route[1]))
                    .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        }
    }

    @Test
    void corsRefusesAnyOtherOriginAndIsAbsentOnCustomerRoutes() throws Exception {
        mvc.perform(options("/api/v1/venue/auth/refresh").header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(post("/api/v1/venue/auth/logout").header("Origin", "https://evil.example")
                        .header(WebOrigins.CSRF_HEADER, WebOrigins.CSRF_HEADER_VALUE))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(options("/api/v1/customer/auth/login").header("Origin", DASHBOARD_ORIGIN)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private ResultActions venueLogin() throws Exception {
        String login = "venue-" + UUID.randomUUID();
        TestAccounts.venue(db, login, passwordHash);
        return login("venue", Map.of("loginIdentifier", login, "password", PASSWORD));
    }

    private ResultActions adminLogin() throws Exception {
        String email = "admin-" + UUID.randomUUID() + "@example.com";
        TestAccounts.admin(email, passwordHash);
        return login("admin", Map.of("email", email, "password", PASSWORD));
    }

    private ResultActions login(String group, Map<String, String> body) throws Exception {
        return mvc.perform(post("/api/v1/" + group + "/auth/login").with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private static String sessionCookieHeader(ResultActions result) {
        List<String> cookies = result.andReturn().getResponse().getHeaders("Set-Cookie");
        assertThat(cookies).hasSize(1);
        return cookies.getFirst();
    }

    private boolean liveSession(String rawToken) {
        return db.sql("SELECT revoked_at IS NULL AND rotated_to IS NULL FROM iam.auth_refresh_token "
                        + "WHERE token_hash = :hash")
                .param("hash", RefreshTokens.hash(rawToken)).query(Boolean.class).single();
    }

    private Object rotatedTo(String rawToken) {
        return db.sql("SELECT rotated_to FROM iam.auth_refresh_token WHERE token_hash = :hash")
                .param("hash", RefreshTokens.hash(rawToken)).query((rs, n) -> rs.getObject(1)).list().getFirst();
    }
}
