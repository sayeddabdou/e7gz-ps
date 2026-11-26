package ps.e7gz.config.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * PCR-010 item 10 (PO decision 15 Nov 2026) at the launch setting, {@code e7gz.payments.fee-collection-enabled=false}:
 * no payment provider collects the money part of a Booking Fee, so the Platform Admin cannot set one above EGP 0 -
 * otherwise a Customer with Points would spend them while a Customer without any pays nothing. PCR-010 item 11 (PO
 * decision 16 Nov 2026, E7-91): the Tournament Creation Fee follows the same rule. Everything else on
 * {@code PUT /admin/fees} still works.
 */
@AutoConfigureMockMvc
class AdminFeesWithoutPaymentCollectionHttpTest extends IntegrationTest {

    static final String FEES = "/api/v1/admin/fees";

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    private Fixtures.Admin admin;
    private String adminToken;

    @BeforeEach
    void signIn() {
        admin = Fixtures.anAdmin().create();
        adminToken = "Bearer " + accessTokens.issue(AuthSubjectType.ADMIN, admin.id());
    }

    @AfterEach
    void backToTheLaunchValues() throws Exception {
        change("""
                {"bookingFeeMode":"PER_BOOKING","tournamentCreationFee":{"amount":"0.00","currency":"EGP"},
                 "reason":"Back to launch."}""").andExpect(status().isOk());
    }

    @Test
    void aBookingFeeAboveZeroIsRefusedOnItsField() throws Exception {
        change("""
                {"bookingFee":{"amount":"5.00","currency":"EGP"},"reason":"Fee switched on."}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("bookingFee"));
        change("""
                {"bookingFeeMode":"PER_HOUR","bookingFeePerHour":{"amount":"0.01","currency":"EGP"},
                 "reason":"Per hour."}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("bookingFeePerHour"));

        assertThat(versionsBy(admin.id())).as("nothing was changed, the mode included").isZero();
        mvc.perform(get(FEES).header("Authorization", adminToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bookingFeeMode.value").value("PER_BOOKING"))
                .andExpect(jsonPath("$.data.bookingFee.value.amount").value("0.00"))
                .andExpect(jsonPath("$.data.bookingFeePerHour.value.amount").value("0.00"));
    }

    /**
     * PCR-010 item 11: a Tournament Creation Fee above 0.00 is refused on its field before any idempotency record, and
     * nothing else in the same request is changed.
     */
    @Test
    void aTournamentCreationFeeAboveZeroIsRefusedOnItsField() throws Exception {
        String key = UUID.randomUUID().toString();
        change(key, """
                {"bookingFeeMode":"PER_HOUR","tournamentCreationFee":{"amount":"25.00","currency":"EGP"},
                 "reason":"Tournament fee switched on."}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("tournamentCreationFee"));
        change("""
                {"tournamentCreationFee":{"amount":"0.01","currency":"EGP"},"reason":"One piastre."}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("tournamentCreationFee"));

        assertThat(versionsBy(admin.id())).as("nothing was changed, the mode included").isZero();
        assertThat(idempotencyRecords(key)).as("refused before any idempotency record").isZero();
        mvc.perform(get(FEES).header("Authorization", adminToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bookingFeeMode.value").value("PER_BOOKING"))
                .andExpect(jsonPath("$.data.tournamentCreationFee.value.amount").value("0.00"));
    }

    @Test
    void zeroFeesAndTheModeCanStillChange() throws Exception {
        change("""
                {"bookingFeeMode":"PER_HOUR","bookingFee":{"amount":"0.00","currency":"EGP"},
                 "bookingFeePerHour":{"amount":"0.00","currency":"EGP"},
                 "tournamentCreationFee":{"amount":"0.00","currency":"EGP"},"reason":"Mode only."}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bookingFeeMode.value").value("PER_HOUR"))
                .andExpect(jsonPath("$.data.tournamentCreationFee.value.amount").value("0.00"));
    }

    private ResultActions change(String body) throws Exception {
        return change(UUID.randomUUID().toString(), body);
    }

    private ResultActions change(String key, String body) throws Exception {
        return mvc.perform(put(FEES).contentType(MediaType.APPLICATION_JSON).header("Authorization", adminToken)
                .header(OpenApiConfig.IDEMPOTENCY_KEY, key).content(body));
    }

    private static int idempotencyRecords(String key) {
        return AdminConfigurationHttpTest.OWNER
                .sql("SELECT count(*) FROM technical.idempotency_record WHERE idempotency_key = :key")
                .param("key", key).query(Integer.class).single();
    }

    private static int versionsBy(UUID admin) {
        return AdminConfigurationHttpTest.OWNER
                .sql("SELECT count(*) FROM config.configuration_version WHERE admin_id = :admin")
                .param("admin", admin).query(Integer.class).single();
    }
}
