package ps.e7gz.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.audit.AuditFixtures;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-23 (S1-P1): the Platform Admin's areas, venues and marketplace status over HTTP, against the migrated database.
 * Covers every allowed and forbidden status change and the area deactivation refusal (the task's "done when"),
 * plus the PO decisions of 2 Oct 2026 (onboarding exit, audit scope, activity replacement).
 */
@AutoConfigureMockMvc
class MarketplaceAdminHttpTest extends IntegrationTest {

    private static final double LAT = 30.0561;
    private static final double LNG = 31.3301;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AccessTokens accessTokens;

    /** Test set-up and checks as the object owner: rows in any state, and reads the app role may not do. */
    private final JdbcClient owner = JdbcClient.create(new SimpleDriverDataSource(
            new Driver(), PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    private UUID adminId;
    private String token;

    @BeforeEach
    void anAdmin() {
        adminId = AuditFixtures.admin();
        token = accessTokens.issue(AuthSubjectType.ADMIN, adminId);
    }

    // ------------------------------------------------------------------------------------------------ areas

    @Test
    void anAdminCreatesAnActiveAreaThatCustomersCanSee() throws Exception {
        String name = "Area " + UUID.randomUUID();
        JsonNode area = data(call(post("/api/v1/admin/areas"), Map.of(
                "name", name, "nameAr", "منطقة", "centroid", Map.of("lat", LAT, "lng", LNG)))
                .andExpect(status().isCreated()));

        assertThat(area.path("active").asBoolean()).isTrue();
        assertThat(area.path("nameAr").asText()).isEqualTo("منطقة");
        assertThat(area.path("centroid").path("lat").asDouble()).isEqualTo(LAT);
        assertThat(area.path("centroid").path("lng").asDouble()).isEqualTo(LNG);
        assertThat(customerAreaNames()).contains(name);
    }

    @Test
    void anAreaIsRenamedAndItsCentroidMoved() throws Exception {
        UUID area = area(true);
        JsonNode updated = data(call(patch("/api/v1/admin/areas/" + area),
                Map.of("name", "Renamed", "centroid", Map.of("lat", 30.1, "lng", 31.2))).andExpect(status().isOk()));

        assertThat(updated.path("name").asText()).isEqualTo("Renamed");
        assertThat(updated.path("centroid").path("lat").asDouble()).isEqualTo(30.1);
        assertThat(updated.path("centroid").path("lng").asDouble()).isEqualTo(31.2);
    }

    /** FR-VEN-47: the database refuses while a venue is ACTIVE or SUSPENDED; the API maps it. */
    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "SUSPENDED"})
    void anAreaWithAnActiveOrSuspendedVenueCannotBeDeactivated(String venueStatus) throws Exception {
        UUID area = area(true);
        venue(area, venueStatus);

        call(post("/api/v1/admin/areas/" + area + "/deactivate"), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("AREA_HAS_ACTIVE_VENUES"));
        assertThat(areaActive(area)).isTrue();
        assertThat(auditCount("AREA_DEACTIVATED", area)).isZero();
    }

    @Test
    void anAreaWithOnlyOnboardingOrInactiveVenuesIsDeactivatedHiddenAndAudited() throws Exception {
        UUID area = area(true);
        venue(area, "ONBOARDING");
        venue(area, "INACTIVE");
        String name = owner.sql("SELECT name FROM geo.area WHERE id = :id").param("id", area)
                .query(String.class).single();

        call(post("/api/v1/admin/areas/" + area + "/deactivate"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(false));

        assertThat(customerAreaNames()).doesNotContain(name);
        assertThat(auditCount("AREA_DEACTIVATED", area)).isEqualTo(1);

        call(post("/api/v1/admin/areas/" + area + "/activate"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(true));
        assertThat(auditCount("AREA_ACTIVATED", area)).isEqualTo(1);

        // Already active: nothing changes and nothing is audited.
        call(post("/api/v1/admin/areas/" + area + "/activate"), null).andExpect(status().isOk());
        assertThat(auditCount("AREA_ACTIVATED", area)).isEqualTo(1);
    }

    @Test
    void anUnknownAreaIsNotFound() throws Exception {
        call(post("/api/v1/admin/areas/" + UUID.randomUUID() + "/deactivate"), null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ------------------------------------------------------------------------------------------------ venues

    @Test
    void aNewVenueStartsInOnboardingWithItsLocationStoredLongitudeFirst() throws Exception {
        UUID area = area(true);
        JsonNode venue = data(call(post("/api/v1/admin/venues"), newVenue(area, List.of("PLAYSTATION", "BILLIARDS")))
                .andExpect(status().isCreated()));

        UUID id = UUID.fromString(venue.path("id").asText());
        assertThat(venue.path("marketplaceStatus").asText()).isEqualTo("ONBOARDING");
        assertThat(venue.path("timezone").asText()).isEqualTo("Africa/Cairo");
        assertThat(venue.path("location").path("lat").asDouble()).isEqualTo(LAT);
        assertThat(venue.path("location").path("lng").asDouble()).isEqualTo(LNG);
        assertThat(venue.path("activities").toString()).contains("PLAYSTATION", "BILLIARDS");
        // PostGIS X is longitude, Y is latitude: a swap would put Cairo in the Indian Ocean.
        Map<String, Object> point = owner.sql(
                        "SELECT ST_X(location::geometry) AS x, ST_Y(location::geometry) AS y FROM venue.venue WHERE id = :id")
                .param("id", id).query().singleRow();
        assertThat(point).containsEntry("x", LNG).containsEntry("y", LAT);
    }

    @Test
    void aVenueCannotBeCreatedInAnInactiveOrUnknownArea() throws Exception {
        call(post("/api/v1/admin/venues"), newVenue(area(false), List.of("PLAYSTATION")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("AREA_INACTIVE"));
        call(post("/api/v1/admin/venues"), newVenue(UUID.randomUUID(), List.of("PLAYSTATION")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("areaId"));
    }

    static Stream<Arguments> statusChanges() {
        List<Arguments> cases = new ArrayList<>();
        String[] statuses = {"ONBOARDING", "ACTIVE", "INACTIVE", "SUSPENDED"};
        Map<String, String> actions = Map.of(
                "onboarding/complete", "INACTIVE", "activate", "ACTIVE", "deactivate", "INACTIVE",
                "suspend", "SUSPENDED");
        for (String from : statuses) {
            for (Map.Entry<String, String> action : actions.entrySet()) {
                boolean allowed = action.getKey().equals("onboarding/complete")
                        ? from.equals("ONBOARDING")
                        : !from.equals("ONBOARDING") && !from.equals(action.getValue());
                cases.add(Arguments.of(from, action.getKey(), action.getValue(), allowed));
            }
        }
        return cases.stream();
    }

    /**
     * Every status x action pair. Allowed: complete onboarding from ONBOARDING (to INACTIVE, never ACTIVE); after
     * that, free switching among ACTIVE, INACTIVE and SUSPENDED. Forbidden: any other action while ONBOARDING (PO
     * decision 2 Oct 2026), completing onboarding twice, and moving to the status the venue already has.
     */
    @ParameterizedTest(name = "{0} --{1}--> {2}: allowed={3}")
    @MethodSource("statusChanges")
    void everyStatusChangeIsAllowedOrRefusedAsDecided(String from, String action, String target, boolean allowed)
            throws Exception {
        UUID venue = venue(area(true), from);
        String reason = "Reason " + UUID.randomUUID();

        ResultActions result = call(post("/api/v1/admin/venues/" + venue + "/" + action), Map.of("reason", reason));

        if (allowed) {
            result.andExpect(status().isOk()).andExpect(jsonPath("$.data.marketplaceStatus").value(target));
            assertThat(marketplaceStatus(venue)).isEqualTo(target);
            Map<String, Object> audit = owner.sql("""
                            SELECT actor_type, actor_id, reason, payload->>'previousStatus' AS previous,
                                   payload->>'newStatus' AS next
                              FROM audit.audit_event
                             WHERE action = 'VENUE_MARKETPLACE_STATUS_CHANGED' AND subject_id = :venue""")
                    .param("venue", venue).query().singleRow();
            assertThat(audit).containsEntry("actor_type", "ADMIN").containsEntry("actor_id", adminId)
                    .containsEntry("reason", reason).containsEntry("previous", from).containsEntry("next", target);
        } else {
            result.andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("MARKETPLACE_STATUS_CHANGE_NOT_ALLOWED"));
            assertThat(marketplaceStatus(venue)).isEqualTo(from);
            assertThat(auditCount("VENUE_MARKETPLACE_STATUS_CHANGED", venue)).isZero();
        }
    }

    /** The area rule is the database's: ACTIVE or SUSPENDED in an inactive area is AREA_INACTIVE. */
    @ParameterizedTest
    @ValueSource(strings = {"activate", "suspend"})
    void aVenueInAnInactiveAreaCannotBecomeActiveOrSuspended(String action) throws Exception {
        UUID area = area(true);
        UUID venue = venue(area, "INACTIVE");
        owner.sql("UPDATE geo.area SET is_active = false WHERE id = :id").param("id", area).update();

        call(post("/api/v1/admin/venues/" + venue + "/" + action), Map.of("reason", "try"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("AREA_INACTIVE"));
        assertThat(marketplaceStatus(venue)).isEqualTo("INACTIVE");
    }

    @Test
    void aStatusChangeNeedsAReasonAndAnExistingVenue() throws Exception {
        UUID venue = venue(area(true), "ONBOARDING");
        call(post("/api/v1/admin/venues/" + venue + "/onboarding/complete"), Map.of("reason", " "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        call(post("/api/v1/admin/venues/" + UUID.randomUUID() + "/activate"), Map.of("reason", "x"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void editingAVenueChangesOnlyTheFieldsSentAndReplacesItsActivities() throws Exception {
        UUID venue = venue(area(true), "ONBOARDING");
        UUID otherArea = area(true);

        JsonNode updated = data(call(patch("/api/v1/admin/venues/" + venue), Map.of(
                "areaId", otherArea, "name", "New name", "location", Map.of("lat", 30.2, "lng", 31.4),
                "activities", List.of("PING_PONG", "BILLIARDS"))).andExpect(status().isOk()));

        assertThat(updated.path("areaId").asText()).isEqualTo(otherArea.toString());
        assertThat(updated.path("name").asText()).isEqualTo("New name");
        assertThat(updated.path("address").asText()).isEqualTo("12 Test St");
        assertThat(updated.path("location").path("lat").asDouble()).isEqualTo(30.2);
        assertThat(updated.path("location").path("lng").asDouble()).isEqualTo(31.4);
        assertThat(activities(venue)).containsExactly("BILLIARDS", "PING_PONG");

        call(patch("/api/v1/admin/venues/" + venue), Map.of("activities", List.of()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("activities"));
    }

    @Test
    void anActivityWithRoomsCannotBeRemoved() throws Exception {
        UUID venue = venue(area(true), "ONBOARDING");
        // A room of the venue's PLAYSTATION activity, written as the owner with triggers off (set-up only).
        try (Connection c = DriverManager.getConnection(PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR);
             Statement replica = c.createStatement();
             PreparedStatement room = c.prepareStatement("""
                     INSERT INTO resource.resource (id, venue_id, activity, name, console_type, base_state,
                                                    operational_restriction)
                     VALUES (gen_random_uuid(), ?, 'PLAYSTATION', 'Room 1', 'PS5', 'ACTIVE', 'NONE')""")) {
            replica.execute("SET session_replication_role = replica");
            room.setObject(1, venue);
            room.executeUpdate();
        }

        call(patch("/api/v1/admin/venues/" + venue), Map.of("activities", List.of("BILLIARDS")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VENUE_ACTIVITY_IN_USE"));
        assertThat(activities(venue)).containsExactly("PLAYSTATION");
    }

    @Test
    void theVenuePageShowsTheVenueAndItsAccountWhenThereIsOne() throws Exception {
        UUID venue = venue(area(true), "ONBOARDING");
        call(get("/api/v1/admin/venues/" + venue), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.venue.id").value(venue.toString()))
                .andExpect(jsonPath("$.data.venueAccount").value(Matchers.nullValue()));

        String login = "login-" + UUID.randomUUID();
        owner.sql("""
                        INSERT INTO iam.venue_account (id, venue_id, login_identifier, password_hash, account_status,
                                                       language_preference)
                        VALUES (gen_random_uuid(), :venue, :login, 'x', 'ENABLED', 'en')""")
                .param("venue", venue).param("login", login).update();
        call(get("/api/v1/admin/venues/" + venue), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.venueAccount.loginIdentifier").value(login))
                .andExpect(jsonPath("$.data.venueAccount.accountStatus").value("ENABLED"));
    }

    @Test
    void theVenueListIsPagedWithAnOpaqueCursor() throws Exception {
        UUID area = area(true);
        for (int i = 0; i < 3; i++) {
            venue(area, "ONBOARDING");
        }
        JsonNode first = data(call(get("/api/v1/admin/venues?limit=2"), null).andExpect(status().isOk()));
        assertThat(first.path("items")).hasSize(2);
        String cursor = first.path("nextCursor").asText();
        assertThat(cursor).isNotBlank();

        JsonNode second = data(call(get("/api/v1/admin/venues?limit=2&cursor=" + cursor), null)
                .andExpect(status().isOk()));
        assertThat(second.path("items").get(0).path("id").asText())
                .isNotEqualTo(first.path("items").get(1).path("id").asText());

        call(get("/api/v1/admin/venues?cursor=not-a-cursor"), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("cursor"));
    }

    @Test
    void theAdminRoutesNeedAnAdminToken() throws Exception {
        mvc.perform(get("/api/v1/admin/areas")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/customer/areas")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private List<String> customerAreaNames() throws Exception {
        JsonNode areas = json.readTree(mvc.perform(get("/api/v1/customer/areas")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
        List<String> names = new ArrayList<>();
        areas.forEach(a -> names.add(a.path("name").asText()));
        return names;
    }

    private static Map<String, Object> newVenue(UUID area, List<String> activities) {
        return Map.of("areaId", area, "name", "Venue " + UUID.randomUUID(), "address", "12 Test St",
                "location", Map.of("lat", LAT, "lng", LNG), "phone", "+201001234567", "activities", activities);
    }

    private UUID area(boolean active) {
        UUID id = UUID.randomUUID();
        owner.sql("INSERT INTO geo.area (id, name, is_active) VALUES (:id, :name, :active)")
                .param("id", id).param("name", "Area " + id).param("active", active).update();
        return id;
    }

    /** A venue with PLAYSTATION, directly in the given marketplace status (set-up as the owner). */
    private UUID venue(UUID area, String marketplaceStatus) {
        UUID id = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
                        VALUES (:id, :area, :name, '12 Test St', 'SRID=4326;POINT(31.2 30.0)'::geography, :status,
                                'Africa/Cairo', '+200000000')""")
                .param("id", id).param("area", area).param("name", "Venue " + id).param("status", marketplaceStatus)
                .update();
        owner.sql("INSERT INTO venue.venue_activity (id, venue_id, activity) VALUES (gen_random_uuid(), :id, 'PLAYSTATION')")
                .param("id", id).update();
        return id;
    }

    private String marketplaceStatus(UUID venue) {
        return owner.sql("SELECT marketplace_status FROM venue.venue WHERE id = :id").param("id", venue)
                .query(String.class).single();
    }

    private boolean areaActive(UUID area) {
        return owner.sql("SELECT is_active FROM geo.area WHERE id = :id").param("id", area)
                .query(Boolean.class).single();
    }

    private List<String> activities(UUID venue) {
        return owner.sql("SELECT activity FROM venue.venue_activity WHERE venue_id = :id ORDER BY activity")
                .param("id", venue).query(String.class).list();
    }

    private long auditCount(String action, UUID subject) {
        return owner.sql("SELECT count(*) FROM audit.audit_event WHERE action = :action AND subject_id = :subject")
                .param("action", action).param("subject", subject).query(Long.class).single();
    }
}
