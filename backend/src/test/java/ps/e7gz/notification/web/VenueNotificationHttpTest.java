package ps.e7gz.notification.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.fixtures.Fixtures.aUser;
import static ps.e7gz.support.fixtures.Fixtures.aVenueAccount;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.NotificationRecipientType;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.notification.api.NotificationRequest;
import ps.e7gz.notification.api.Notifications;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.TestCommands;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-55 (PO decision 12 Oct 2026, FR-NTF-09): the venue's in-Dashboard list - {@code GET /venue/notifications} and
 * {@code POST /venue/notifications/{id}/read} - through the real security chain, error mapping and database. Same
 * shape and paging as the customer's list.
 */
@AutoConfigureMockMvc
class VenueNotificationHttpTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    Notifications notifications;

    @Autowired
    CommandRunner runner;

    @Autowired
    DataSource appDataSource;

    @Autowired
    ObjectMapper json;

    JdbcClient db;
    Fixtures.VenueAccount mine;
    String myToken;
    String otherToken;
    CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(appDataSource);
        correlation = CorrelationId.open(CorrelationId.generate());
        mine = aVenueAccount().create();
        myToken = accessTokens.issue(AuthSubjectType.VENUE, mine.id());
        otherToken = accessTokens.issue(AuthSubjectType.VENUE, aVenueAccount().create().id());
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    @Test
    void theListIsMyVenuesNotificationsNewestFirstWithCursorPaging() throws Exception {
        UUID first = notifyVenue(mine.venueId(), Map.of("minutes", 15));
        UUID second = notifyVenue(mine.venueId(), Map.of("minutes", 10));
        UUID third = notifyVenue(mine.venueId(), Map.of("minutes", 5));
        notifyVenue(aVenueAccount().create().venueId(), Map.of());                 // another venue's
        notifyCustomer(aUser().create().id());                                      // a customer's

        JsonNode page1 = body(mvc.perform(as(myToken, get("/api/v1/venue/notifications?limit=2")))
                .andExpect(status().isOk()).andReturn());
        assertThat(page1.at("/data/items").size()).isEqualTo(2);
        assertThat(page1.at("/data/items/0/id").asText()).isEqualTo(third.toString());
        assertThat(page1.at("/data/items/1/id").asText()).isEqualTo(second.toString());
        assertThat(page1.at("/data/items/0/code").asText()).isEqualTo("VENUE_MAINTENANCE_STARTING_SOON");
        assertThat(page1.at("/data/items/0/readAt").isNull()).isTrue();

        JsonNode page2 = body(mvc.perform(as(myToken, get("/api/v1/venue/notifications?limit=2&cursor="
                        + page1.at("/data/nextCursor").asText())))
                .andExpect(status().isOk()).andReturn());
        assertThat(page2.at("/data/items").size()).isEqualTo(1);
        assertThat(page2.at("/data/items/0/id").asText()).isEqualTo(first.toString());
        assertThat(page2.at("/data/items/0/params/minutes").asInt()).isEqualTo(15);
        assertThat(page2.at("/data/nextCursor").isNull()).isTrue();
    }

    @Test
    void aMalformedCursorOrLimitIsValidationFailed() throws Exception {
        mvc.perform(as(myToken, get("/api/v1/venue/notifications?cursor=%21%21")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("cursor"));
        mvc.perform(as(myToken, get("/api/v1/venue/notifications?limit=101")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void readingIsRecordedOnceAndAnotherVenuesNotificationIsNotFound() throws Exception {
        UUID id = notifyVenue(mine.venueId(), Map.of());

        mvc.perform(as(myToken, post("/api/v1/venue/notifications/{id}/read", id))).andExpect(status().isNoContent());
        Object firstRead = readAt(id);
        mvc.perform(as(myToken, post("/api/v1/venue/notifications/{id}/read", id))).andExpect(status().isNoContent());

        assertThat(firstRead).isNotNull();
        assertThat(readAt(id)).isEqualTo(firstRead);
        mvc.perform(as(otherToken, post("/api/v1/venue/notifications/{id}/read", id)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(as(myToken, post("/api/v1/venue/notifications/{id}/read", UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void aCustomerTokenCannotReadTheVenueList() throws Exception {
        String customer = accessTokens.issue(AuthSubjectType.CUSTOMER, aUser().verified().create().id());

        mvc.perform(as(customer, get("/api/v1/venue/notifications"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/venue/notifications")).andExpect(status().isUnauthorized());
    }

    private UUID notifyVenue(UUID venueId, Map<String, Object> params) {
        return runner.run(TestCommands.under(CommandScope.shared(LockSet.empty()), context ->
                notifications.notify(context, NotificationRequest.of("VENUE_MAINTENANCE_STARTING_SOON",
                        NotificationRecipientType.VENUE, venueId, params))));
    }

    private void notifyCustomer(UUID user) {
        runner.run(TestCommands.under(CommandScope.shared(LockSet.empty()), context ->
                notifications.notify(context, NotificationRequest.of("POINTS_EARNED",
                        NotificationRecipientType.CUSTOMER, user, Map.of()))));
    }

    private Object readAt(UUID id) {
        return db.sql("SELECT read_at FROM notification.notification WHERE id = :id").param("id", id).query()
                .singleValue();
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + token);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
