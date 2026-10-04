package ps.e7gz.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import ps.e7gz.audit.AuditFixtures;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/** E7-26 carry-forward needed by E7-37: real admin resource list/create during onboarding. */
@AutoConfigureMockMvc
class AdminVenueResourceHttpTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccessTokens accessTokens;

    private final JdbcClient owner = JdbcClient.create(new SimpleDriverDataSource(
            new Driver(), PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    private UUID adminId;
    private String token;
    private UUID venueId;

    @BeforeEach
    void setup() {
        adminId = AuditFixtures.admin();
        token = accessTokens.issue(AuthSubjectType.ADMIN, adminId);
        UUID areaId = UUID.randomUUID();
        venueId = UUID.randomUUID();
        owner.sql("INSERT INTO geo.area(id, name, is_active) VALUES (:id, :name, true)")
                .param("id", areaId).param("name", "Resource area " + areaId).update();
        owner.sql("""
                        INSERT INTO venue.venue(id, area_id, name, address, location, marketplace_status, timezone,
                                                phone, created_at)
                        VALUES (:id, :area, :name, 'Address', ST_SetSRID(ST_MakePoint(31.33, 30.05), 4326),
                                'ONBOARDING', 'Africa/Cairo', '+201001234567', clock_timestamp())""")
                .param("id", venueId).param("area", areaId).param("name", "Resource venue " + venueId).update();
        for (String activity : new String[]{"PLAYSTATION", "BILLIARDS", "PING_PONG"}) {
            owner.sql("INSERT INTO venue.venue_activity(id, venue_id, activity) VALUES (:id, :venue, :activity)")
                    .param("id", UUID.randomUUID()).param("venue", venueId).param("activity", activity).update();
        }
    }

    @Test
    void createsAssignedNamesListsBothStatusesAndWritesInitialHistory() throws Exception {
        create(Map.of("activity", "PLAYSTATION", "consoleType", "PS5"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Room 1"))
                .andExpect(jsonPath("$.data.baseState").value("ACTIVE"))
                .andExpect(jsonPath("$.data.operationalRestriction").value("NONE"));
        create(Map.of("activity", "PLAYSTATION", "consoleType", "PS4"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.name").value("Room 2"));
        create(Map.of("activity", "BILLIARDS"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.name").value("Pool Table 1"));

        mvc.perform(get("/api/v1/admin/venues/" + venueId + "/resources")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));

        assertThat(owner.sql("SELECT count(*) FROM resource.resource_state_change c JOIN resource.resource r "
                        + "ON r.id = c.resource_id WHERE r.venue_id = :venue AND c.actor_type = 'ADMIN' "
                        + "AND c.actor_id = :admin AND c.from_base_state IS NULL AND c.to_base_state = 'ACTIVE' "
                        + "AND c.from_restriction IS NULL AND c.to_restriction = 'NONE'")
                .param("venue", venueId).param("admin", adminId).query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void assignsTheNumberAfterTheHighestExistingResourceIncludingArchivedOnes() throws Exception {
        owner.sql("""
                        INSERT INTO resource.resource(id, venue_id, activity, name, console_type, base_state,
                                                      operational_restriction)
                        VALUES (:id, :venue, 'PLAYSTATION', 'Room 7', 'PS5', 'ARCHIVED', 'NONE')""")
                .param("id", UUID.randomUUID()).param("venue", venueId).update();

        create(Map.of("activity", "PLAYSTATION", "consoleType", "PS4"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.name").value("Room 8"));
    }

    @Test
    void rejectsConsoleMismatchesAndActivitiesTheVenueDoesNotOfferAsFieldErrors() throws Exception {
        create(Map.of("activity", "PLAYSTATION"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("consoleType"));
        create(Map.of("activity", "BILLIARDS", "consoleType", "PS5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("consoleType"));

        owner.sql("DELETE FROM venue.venue_activity WHERE venue_id = :venue AND activity = 'PING_PONG'")
                .param("venue", venueId).update();
        create(Map.of("activity", "PING_PONG"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("activity"));
    }

    @Test
    void unknownVenueIsNotFoundForListAndCreate() throws Exception {
        UUID unknown = UUID.randomUUID();
        mvc.perform(get("/api/v1/admin/venues/" + unknown + "/resources")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/admin/venues/" + unknown + "/resources")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("activity", "BILLIARDS"))))
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions create(Map<String, String> body) throws Exception {
        return mvc.perform(post("/api/v1/admin/venues/" + venueId + "/resources")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body)));
    }
}
