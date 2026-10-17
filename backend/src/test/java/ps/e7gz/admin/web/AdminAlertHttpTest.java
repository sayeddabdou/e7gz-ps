package ps.e7gz.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.fixtures.Fixtures.aVenueAccount;
import static ps.e7gz.support.fixtures.Fixtures.anAdmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-56 (PO decisions 15 Oct 2026): {@code GET /admin/alerts} and {@code POST /admin/alerts/{id}/acknowledge} through
 * the real security chain, error mapping and database - one audited acknowledgement per alert.
 */
@AutoConfigureMockMvc
class AdminAlertHttpTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    DataSource appDataSource;

    @Autowired
    ObjectMapper json;

    JdbcClient db;
    UUID admin;
    UUID otherAdmin;
    String adminToken;
    String otherAdminToken;
    /** Each test filters on its own kind, so alerts of other tests never get in the way. */
    String kind;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(appDataSource);
        admin = anAdmin().create().id();
        otherAdmin = anAdmin().create().id();
        adminToken = accessTokens.issue(AuthSubjectType.ADMIN, admin);
        otherAdminToken = accessTokens.issue(AuthSubjectType.ADMIN, otherAdmin);
        kind = "TEST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    @Test
    void listsOpenAlertsNewestFirstWithTheirFacts() throws Exception {
        UUID first = raise("SEV_2", "{\"bookingId\": \"b-1\"}");
        UUID second = raise("SEV_1", null);

        JsonNode page = list("?kind=" + kind, adminToken);

        assertThat(ids(page)).containsExactly(second, first);
        JsonNode newest = page.path("data").path("items").get(0);
        assertThat(newest.path("kind").asText()).isEqualTo(kind);
        assertThat(newest.path("severity").asText()).isEqualTo("SEV_1");
        assertThat(newest.path("subjectType").asText()).isEqualTo("BOOKING");
        assertThat(newest.path("payload").isNull()).isTrue();
        assertThat(newest.path("acknowledgedAt").isNull()).isTrue();
        assertThat(page.path("data").path("items").get(1).path("payload").path("bookingId").asText())
                .isEqualTo("b-1");
    }

    @Test
    void filtersBySeverityAndPagesWithACursor() throws Exception {
        List<UUID> raised = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            raised.add(raise("SEV_3", null));
        }
        raise("SEV_1", null);

        JsonNode first = list("?kind=" + kind + "&severity=SEV_3&limit=2", adminToken);
        String cursor = first.path("data").path("nextCursor").asText();
        JsonNode second = list("?kind=" + kind + "&severity=SEV_3&limit=2&cursor=" + cursor, adminToken);

        assertThat(ids(first)).containsExactly(raised.get(2), raised.get(1));
        assertThat(ids(second)).containsExactly(raised.get(0));
        assertThat(second.path("data").path("nextCursor").isNull()).isTrue();
    }

    @Test
    void aMalformedCursorIsValidationFailed() throws Exception {
        mvc.perform(get("/api/v1/admin/alerts?cursor=not-a-cursor").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("cursor"));
    }

    @Test
    void acknowledgingMovesTheAlertToTheAcknowledgedListAndAuditsIt() throws Exception {
        UUID alert = raise("SEV_1", null);

        mvc.perform(post("/api/v1/admin/alerts/{id}/acknowledge", alert)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"  Checked by hand.  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(alert.toString()))
                .andExpect(jsonPath("$.data.acknowledgedByAdminId").value(admin.toString()))
                .andExpect(jsonPath("$.data.acknowledgedAt").isNotEmpty());

        assertThat(ids(list("?kind=" + kind, adminToken))).isEmpty();
        assertThat(ids(list("?kind=" + kind + "&acknowledged=true", adminToken))).containsExactly(alert);
        assertThat(db.sql("""
                        SELECT actor_type || ':' || actor_id || ':' || reason FROM audit.audit_event
                         WHERE action = 'ADMIN_ALERT_ACKNOWLEDGED' AND subject_id = :id""")
                .param("id", alert).query(String.class).list())
                .containsExactly("ADMIN:" + admin + ":Checked by hand.");
    }

    /** The database's rule: the same admin again gets the same alert back, with nothing new written. */
    @Test
    void theSameAdminAcknowledgingAgainGetsTheSameAlert() throws Exception {
        UUID alert = raise("SEV_2", null);
        String firstAt = acknowledge(alert, adminToken).path("data").path("acknowledgedAt").asText();

        JsonNode again = acknowledge(alert, adminToken);

        assertThat(again.path("data").path("acknowledgedAt").asText()).isEqualTo(firstAt);
        assertThat(db.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'ADMIN_ALERT_ACKNOWLEDGED' "
                + "AND subject_id = :id").param("id", alert).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void anotherAdminGetsAlreadyAcknowledged() throws Exception {
        UUID alert = raise("SEV_2", null);
        acknowledge(alert, adminToken);

        mvc.perform(post("/api/v1/admin/alerts/{id}/acknowledge", alert)
                        .header("Authorization", "Bearer " + otherAdminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMIN_ALERT_ALREADY_ACKNOWLEDGED"));
        assertThat(db.sql("SELECT acknowledged_by_admin_id FROM admin.admin_alert WHERE id = :id")
                .param("id", alert).query(UUID.class).single()).isEqualTo(admin);
    }

    @Test
    void anUnknownAlertIsNotFound() throws Exception {
        mvc.perform(post("/api/v1/admin/alerts/{id}/acknowledge", UUID.randomUUID())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void onlyAPlatformAdminReachesTheInbox() throws Exception {
        String venueToken = accessTokens.issue(AuthSubjectType.VENUE, aVenueAccount().create().id());

        mvc.perform(get("/api/v1/admin/alerts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/alerts").header("Authorization", "Bearer " + venueToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/alerts/{id}/acknowledge", raise("SEV_1", null))
                        .header("Authorization", "Bearer " + venueToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private UUID raise(String severity, String payload) {
        return db.sql("SELECT admin.raise_alert(:kind, :severity, 'BOOKING', gen_random_uuid(), "
                        + "CAST(:payload AS jsonb), gen_random_uuid())")
                .param("kind", kind).param("severity", severity).param("payload", payload)
                .query(UUID.class).single();
    }

    private JsonNode list(String query, String token) throws Exception {
        String body = mvc.perform(get("/api/v1/admin/alerts" + query).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode acknowledge(UUID alert, String token) throws Exception {
        String body = mvc.perform(post("/api/v1/admin/alerts/{id}/acknowledge", alert)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.path("data").path("items").forEach(item -> ids.add(UUID.fromString(item.path("id").asText())));
        return ids;
    }
}
