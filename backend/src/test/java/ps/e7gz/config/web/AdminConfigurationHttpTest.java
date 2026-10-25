package ps.e7gz.config.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-68 - the Sprint 4 configuration routes (E7-65 contract, PO decision D3) through the real security chain and
 * database, with PCR-010's additions: {@code /admin/fees} (the booking fee mode, the per-booking and per-hour fees, the
 * tournament creation fee) and {@code /admin/points/settings} (the points values, the wallet-refund switch and the
 * Points per fee unit). Each value comes with its effectiveFrom; a change is audited, takes effect now for later events
 * only (FR-ADM-11), skips unchanged values, needs a reason and the Idempotency-Key (D6).
 *
 * <p>The values are global, so every test puts the launch values back through the same routes.
 */
@AutoConfigureMockMvc
class AdminConfigurationHttpTest extends IntegrationTest {

    static final String FEES = "/api/v1/admin/fees";
    static final String SETTINGS = "/api/v1/admin/points/settings";
    static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ObjectMapper json;

    private Fixtures.Admin admin;
    private String adminToken;

    @BeforeEach
    void signIn() {
        admin = Fixtures.anAdmin().create();
        adminToken = "Bearer " + accessTokens.issue(AuthSubjectType.ADMIN, admin.id());
    }

    @AfterEach
    void backToTheLaunchValues() throws Exception {
        change(FEES, """
                {"bookingFeeMode":"PER_BOOKING","bookingFee":{"amount":"0.00","currency":"EGP"},
                 "bookingFeePerHour":{"amount":"0.00","currency":"EGP"},
                 "tournamentCreationFee":{"amount":"0.00","currency":"EGP"},"reason":"Back to launch."}""")
                .andExpect(status().isOk());
        change(SETTINGS, """
                {"attendedPoints":200,"referralPoints":1000,"walletRefundsEnabled":false,"pointsPerFeeUnit":10000,
                 "reason":"Back to launch."}""").andExpect(status().isOk());
    }

    @Test
    void theLaunchValuesEachWithTheInstantTheyTookEffect() throws Exception {
        JsonNode fees = data(mvc.perform(get(FEES).header("Authorization", adminToken)).andExpect(status().isOk()));
        assertThat(fees.at("/bookingFeeMode/value").asText()).isEqualTo("PER_BOOKING");
        assertThat(fees.at("/bookingFee/value/amount").asText()).isEqualTo("0.00");
        assertThat(fees.at("/bookingFeePerHour/value/amount").asText()).isEqualTo("0.00");
        assertThat(fees.at("/tournamentCreationFee/value/amount").asText()).isEqualTo("0.00");
        for (String field : new String[] {"bookingFeeMode", "bookingFee", "bookingFeePerHour", "tournamentCreationFee"}) {
            assertThat(Instant.parse(fees.at("/" + field + "/effectiveFrom").asText())).as(field).isNotNull();
        }

        JsonNode settings = data(mvc.perform(get(SETTINGS).header("Authorization", adminToken))
                .andExpect(status().isOk()));
        assertThat(settings.at("/attendedPoints/value").asInt()).isEqualTo(200);
        assertThat(settings.at("/referralPoints/value").asInt()).isEqualTo(1000);
        assertThat(settings.at("/walletRefundsEnabled/value").asBoolean()).isFalse();
        assertThat(settings.at("/pointsPerFeeUnit/value").asInt()).as("PCR-010 launch value").isEqualTo(10_000);
        assertThat(Instant.parse(settings.at("/pointsPerFeeUnit/effectiveFrom").asText())).isNotNull();
    }

    @Test
    void aFeeChangeIsAuditedTakesEffectNowAndLeavesTheOmittedFeesAsTheyWere() throws Exception {
        JsonNode before = data(mvc.perform(get(FEES).header("Authorization", adminToken)).andExpect(status().isOk()));

        JsonNode after = data(change(FEES, """
                {"bookingFeeMode":"PER_HOUR","bookingFeePerHour":{"amount":"10.00","currency":"EGP"},
                 "reason":"Hourly fee for the new season."}""").andExpect(status().isOk()));

        assertThat(after.at("/bookingFeeMode/value").asText()).isEqualTo("PER_HOUR");
        assertThat(after.at("/bookingFeePerHour/value/amount").asText()).isEqualTo("10.00");
        assertThat(Instant.parse(after.at("/bookingFeeMode/effectiveFrom").asText()))
                .isAfter(Instant.parse(before.at("/bookingFeeMode/effectiveFrom").asText()));
        assertThat(after.get("bookingFee")).as("omitted: unchanged, same instant").isEqualTo(before.get("bookingFee"));
        assertThat(after.get("tournamentCreationFee")).isEqualTo(before.get("tournamentCreationFee"));
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM config.configuration_version
                         WHERE key IN ('booking.fee_mode', 'booking.fee_per_hour') AND admin_id = :admin
                           AND reason = 'Hourly fee for the new season.'""")
                .param("admin", admin.id()).query(Integer.class).single()).isEqualTo(2);
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM audit.audit_event
                         WHERE action = 'CONFIGURATION_CHANGED' AND actor_id = :admin""")
                .param("admin", admin.id()).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void anUnchangedValueChangesNothingAndNoValueIsAValidationFailure() throws Exception {
        change(FEES, """
                {"bookingFee":{"amount":"0.00","currency":"EGP"},"reason":"Same fee."}""").andExpect(status().isOk());
        change(SETTINGS, """
                {"pointsPerFeeUnit":10000,"reason":"Same unit."}""").andExpect(status().isOk());
        assertThat(versionsBy(admin.id())).isZero();

        change(FEES, """
                {"reason":"Nothing."}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        change(SETTINGS, """
                {"reason":"Nothing."}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void thePointsPerFeeUnitIsAnEvenWholeNumberFromTwo() throws Exception {
        for (String unit : new String[] {"9999", "1", "0"}) {
            change(SETTINGS, "{\"pointsPerFeeUnit\":" + unit + ",\"reason\":\"Odd unit.\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.field").value("pointsPerFeeUnit"));
        }
        assertThat(versionsBy(admin.id())).isZero();

        JsonNode after = data(change(SETTINGS, """
                {"pointsPerFeeUnit":8000,"reason":"Cheaper hours."}""").andExpect(status().isOk()));
        assertThat(after.at("/pointsPerFeeUnit/value").asInt()).isEqualTo(8000);
        assertThat(after.at("/attendedPoints/value").asInt()).isEqualTo(200);
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM audit.audit_event
                         WHERE action = 'LOYALTY_CONFIGURATION_CHANGED' AND actor_id = :admin
                           AND payload->>'key' = 'loyalty.points_per_fee_unit'""")
                .param("admin", admin.id()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void anExactReplayAnswersTheSameAndChangesOnceAndAChangedRequestIsRefused() throws Exception {
        UUID key = UUID.randomUUID();
        String body = """
                {"bookingFee":{"amount":"5.00","currency":"EGP"},"reason":"Fee switched on."}""";

        JsonNode first = data(change(FEES, body, key).andExpect(status().isOk()));
        JsonNode replay = data(change(FEES, body, key).andExpect(status().isOk()));
        assertThat(replay).isEqualTo(first);
        assertThat(versionsBy(admin.id())).isEqualTo(1);

        change(FEES, """
                {"bookingFee":{"amount":"6.00","currency":"EGP"},"reason":"Fee switched on."}""", key)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(versionsBy(admin.id())).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private ResultActions change(String path, String body) throws Exception {
        return change(path, body, UUID.randomUUID());
    }

    private ResultActions change(String path, String body, UUID key) throws Exception {
        return mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).header("Authorization", adminToken)
                .header(OpenApiConfig.IDEMPOTENCY_KEY, key.toString()).content(body));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private static int versionsBy(UUID admin) {
        return OWNER.sql("SELECT count(*) FROM config.configuration_version WHERE admin_id = :admin")
                .param("admin", admin).query(Integer.class).single();
    }
}
