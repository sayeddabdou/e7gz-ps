package ps.e7gz.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import ps.e7gz.support.IntegrationTest;

/**
 * Sprint 0 review S-7 (PO decisions 2 and 15 Oct 2026; E7-57): 50 failed logins of one account in an hour, from any
 * IPs, pause its login for 15 minutes - even the right password is refused - and raise one SEV_3
 * ACCOUNT_LOGIN_LIMIT_REACHED alert with the audience only.
 */
@AutoConfigureMockMvc
class AccountLoginLimitHttpTest extends IntegrationTest {

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
    /** Every attempt from its own address, so the (identifier + IP) limit of 10 never applies. */
    final AtomicInteger ip = new AtomicInteger();

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(appDataSource);
    }

    @Test
    void fiftyFailuresFromAnyIpsPauseTheAccountAndRaiseOneAlert() throws Exception {
        TestCustomers.Customer customer = TestCustomers.insert(db, true, passwords.encode(PASSWORD));
        for (int i = 1; i < AccountLoginLimiter.MAX_FAILURES; i++) {
            login(customer.username(), "Wr0ngPassword").andExpect(status().isUnauthorized());
        }
        assertThat(alerts(customer.id())).as("not before the 50th").isEmpty();

        login(customer.username(), "Wr0ngPassword").andExpect(status().isUnauthorized());     // the 50th

        login(customer.username(), PASSWORD).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        login(customer.localPhone(), PASSWORD).andExpect(status().isTooManyRequests());
        assertThat(alerts(customer.id()))
                .containsExactly("ACCOUNT_LOGIN_LIMIT_REACHED SEV_3 USER {\"audience\": \"CUSTOMER\"}");
        assertThat(counter(customer.id())).containsEntry("action_class", "CUSTOMER_ACCOUNT");
    }

    @Test
    void theAccountLogsInAgainOnceThePauseIsOver() throws Exception {
        TestCustomers.Customer customer = TestCustomers.insert(db, true, passwords.encode(PASSWORD));
        for (int i = 0; i < AccountLoginLimiter.MAX_FAILURES; i++) {
            login(customer.username(), "Wr0ngPassword");
        }
        db.sql("""
                        UPDATE iam.security_rate_limit_counter SET cooldown_until = now() - interval '1 second'
                         WHERE limiter_type = 'LOGIN' AND subject_key_hash = :key""")
                .param("key", AccountLoginLimiter.keyHash(customer.id())).update();     // 15 minutes later

        login(customer.username(), PASSWORD).andExpect(status().isOk());
    }

    @Test
    void failuresBelowTheLimitDoNotPause() throws Exception {
        TestCustomers.Customer customer = TestCustomers.insert(db, true, passwords.encode(PASSWORD));
        for (int i = 0; i < 3; i++) {
            login(customer.username(), "Wr0ngPassword").andExpect(status().isUnauthorized());
        }

        login(customer.username(), PASSWORD).andExpect(status().isOk());
        assertThat(counter(customer.id())).containsEntry("counter", 3);
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private ResultActions login(String identifier, String password) throws Exception {
        int n = ip.incrementAndGet();
        return mvc.perform(post("/api/v1/customer/auth/login").with(r -> {
            r.setRemoteAddr("10.77." + (n / 256) + "." + (n % 256));
            return r;
        }).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("usernameOrPhone", identifier, "password", password))));
    }

    private List<String> alerts(UUID accountId) {
        return db.sql("""
                        SELECT kind || ' ' || severity || ' ' || subject_type || ' ' || payload::text
                          FROM admin.admin_alert WHERE subject_id = :id""")
                .param("id", accountId).query(String.class).list();
    }

    private Map<String, Object> counter(UUID accountId) {
        return db.sql("""
                        SELECT action_class, counter FROM iam.security_rate_limit_counter
                         WHERE limiter_type = 'LOGIN' AND subject_key_hash = :key""")
                .param("key", AccountLoginLimiter.keyHash(accountId)).query().singleRow();
    }
}
