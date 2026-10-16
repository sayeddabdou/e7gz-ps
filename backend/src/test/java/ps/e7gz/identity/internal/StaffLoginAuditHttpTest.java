package ps.e7gz.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.identity.internal.AuthSessionsHttpTest.refreshCookie;
import static ps.e7gz.identity.internal.AuthSessionsHttpTest.webSessionCall;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-56 (Sprint 0 review; names approved by the PO on 15 Oct 2026): Platform Admin and venue account logins leave one
 * audit row each - account id, audience and outcome, never the identifier, the password or the IP - and a reused
 * rotated refresh token raises one REFRESH_TOKEN_REUSE alert (arch 10 section 2.3).
 */
@AutoConfigureMockMvc
class StaffLoginAuditHttpTest extends IntegrationTest {

    private static final String PASSWORD = "Passw0rd1";
    private static final String WRONG = "Wr0ngPassword";

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

    @Test
    void aVenueLoginIsAuditedOnSuccessAndOnEachRefusal() throws Exception {
        String login = "venue-" + UUID.randomUUID();
        UUID account = TestAccounts.venue(db, login, passwordHash);

        login("venue", Map.of("loginIdentifier", login, "password", PASSWORD)).andExpect(status().isOk());
        login("venue", Map.of("loginIdentifier", login, "password", WRONG)).andExpect(status().isUnauthorized());
        TestAccounts.setVenueStatus(db, account, VenueAccountStatus.DISABLED);
        login("venue", Map.of("loginIdentifier", login, "password", PASSWORD)).andExpect(status().isForbidden());

        assertThat(audits(account)).containsExactly(
                "VENUE_ACCOUNT_LOGIN_SUCCEEDED VENUE " + account + " {\"outcome\": \"SUCCEEDED\", \"audience\": \"VENUE\"}",
                "VENUE_ACCOUNT_LOGIN_FAILED SYSTEM null {\"outcome\": \"WRONG_PASSWORD\", \"audience\": \"VENUE\"}",
                "VENUE_ACCOUNT_LOGIN_FAILED SYSTEM null "
                        + "{\"outcome\": \"ACCOUNT_NOT_ALLOWED\", \"audience\": \"VENUE\"}");
        assertNothingIdentifying(account, login);
    }

    @Test
    void anAdminLoginIsAuditedOnSuccessAndOnARefusal() throws Exception {
        String email = "admin-" + UUID.randomUUID() + "@e7gz.test";
        UUID admin = TestAccounts.admin(email, passwordHash);

        login("admin", Map.of("email", email, "password", WRONG)).andExpect(status().isUnauthorized());
        login("admin", Map.of("email", email, "password", PASSWORD)).andExpect(status().isOk());

        assertThat(audits(admin)).containsExactly(
                "ADMIN_LOGIN_FAILED SYSTEM null {\"outcome\": \"WRONG_PASSWORD\", \"audience\": \"ADMIN\"}",
                "ADMIN_LOGIN_SUCCEEDED ADMIN " + admin + " {\"outcome\": \"SUCCEEDED\", \"audience\": \"ADMIN\"}");
        assertNothingIdentifying(admin, email);
    }

    /** PO decision 15 Oct 2026: no account, no subject - not audited (the rate limiter covers it). */
    @Test
    void anUnknownIdentifierLeavesNoAuditRow() throws Exception {
        String unknown = "nobody-" + UUID.randomUUID();
        long before = loginAuditCount();

        login("venue", Map.of("loginIdentifier", unknown, "password", PASSWORD))
                .andExpect(status().isUnauthorized());

        assertThat(loginAuditCount()).isEqualTo(before);
        assertThat(db.sql("SELECT count(*) FROM audit.audit_event WHERE payload::text LIKE :unknown")
                .param("unknown", "%" + unknown + "%").query(Long.class).single()).isZero();
    }

    @Test
    void customerLoginsAreNotAudited() throws Exception {
        long before = loginAuditCount();

        login("customer", Map.of("usernameOrPhone", "nobody" + ThreadLocalRandom.current().nextInt(1_000_000),
                "password", PASSWORD)).andExpect(status().isUnauthorized());

        assertThat(loginAuditCount()).isEqualTo(before);
    }

    /** arch 10 section 2.3: the chain is revoked and one SEV_2 alert names the account - never the token. */
    @Test
    void reusingARotatedTokenRaisesOneRefreshTokenReuseAlert() throws Exception {
        String login = "venue-" + UUID.randomUUID();
        UUID account = TestAccounts.venue(db, login, passwordHash);
        String a = refreshCookie(login("venue", Map.of("loginIdentifier", login, "password", PASSWORD)));
        mvc.perform(webSessionCall("venue", "refresh", a)).andExpect(status().isOk());

        mvc.perform(webSessionCall("venue", "refresh", a)).andExpect(status().isUnauthorized());

        List<Map<String, Object>> alerts = db.sql("""
                        SELECT kind, severity, subject_type, payload::text AS payload FROM admin.admin_alert
                         WHERE subject_id = :account""")
                .param("account", account).query().listOfRows();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst()).containsEntry("kind", "REFRESH_TOKEN_REUSE")
                .containsEntry("severity", "SEV_2")
                .containsEntry("subject_type", "VENUE_ACCOUNT")
                .containsEntry("payload", "{\"audience\": \"VENUE\"}");
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private ResultActions login(String group, Map<String, String> body) throws Exception {
        return mvc.perform(post("/api/v1/" + group + "/auth/login").with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    /** "action actorType actorId payload", oldest first. */
    private List<String> audits(UUID account) {
        return db.sql("""
                        SELECT action || ' ' || actor_type || ' ' || coalesce(actor_id::text, 'null') || ' '
                               || payload::text
                          FROM audit.audit_event
                         WHERE subject_id = :account AND action LIKE '%LOGIN%'
                         ORDER BY occurred_at, id""")
                .param("account", account).query(String.class).list();
    }

    private void assertNothingIdentifying(UUID account, String identifier) {
        assertThat(db.sql("""
                        SELECT count(*) FROM audit.audit_event
                         WHERE subject_id = :account
                           AND (payload::text ILIKE :identifier OR payload::text LIKE :password
                                OR payload::text LIKE :ip)""")
                .param("account", account).param("identifier", "%" + identifier + "%")
                .param("password", "%" + PASSWORD + "%").param("ip", "%" + ip + "%")
                .query(Long.class).single()).isZero();
    }

    private long loginAuditCount() {
        return db.sql("SELECT count(*) FROM audit.audit_event WHERE action LIKE '%LOGIN%'").query(Long.class).single();
    }
}
