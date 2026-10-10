package ps.e7gz.notification.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.fixtures.Fixtures.aUser;
import static ps.e7gz.support.fixtures.Fixtures.anAdmin;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.NotificationRecipientType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.notification.api.NotificationRequest;
import ps.e7gz.notification.api.Notifications;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-43 over HTTP: the E7-39 customer notification routes and the admin "try again", through the real security chain,
 * error mapping and database.
 */
@AutoConfigureMockMvc
class NotificationHttpTest extends IntegrationTest {

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
    UUID me;
    String myToken;
    String otherToken;
    CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(appDataSource);
        correlation = CorrelationId.open(CorrelationId.generate());
        me = aUser().verified().create().id();
        myToken = accessTokens.issue(AuthSubjectType.CUSTOMER, me);
        otherToken = accessTokens.issue(AuthSubjectType.CUSTOMER, aUser().verified().create().id());
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    // ------------------------------------------------------------------------------------------------ the inbox

    @Test
    void theListIsMyInAppNotificationsNewestFirstWithCursorPaging() throws Exception {
        UUID first = notify(me, "POINTS_EARNED", Map.of("points", 10));
        UUID second = notify(me, "BAN_EXPIRED", Map.of());
        UUID third = notify(me, "VERIFIED_PLAYER_UNLOCKED", Map.of());
        notify(me, "EMAIL_VERIFICATION", Map.of());                               // email only: not in the list
        notify(aUser().create().id(), "POINTS_EARNED", Map.of());                  // someone else's

        JsonNode page1 = body(mvc.perform(as(myToken, get("/api/v1/customer/notifications?limit=2")))
                .andExpect(status().isOk()).andReturn());
        assertThat(page1.at("/data/items/0/id").asText()).isEqualTo(third.toString());
        assertThat(page1.at("/data/items/1/id").asText()).isEqualTo(second.toString());
        assertThat(page1.at("/data/items/0/readAt").isNull()).isTrue();

        JsonNode page2 = body(mvc.perform(as(myToken, get("/api/v1/customer/notifications?limit=2&cursor="
                        + page1.at("/data/nextCursor").asText())))
                .andExpect(status().isOk()).andReturn());
        assertThat(page2.at("/data/items").size()).isEqualTo(1);
        assertThat(page2.at("/data/items/0/id").asText()).isEqualTo(first.toString());
        assertThat(page2.at("/data/items/0/code").asText()).isEqualTo("POINTS_EARNED");
        assertThat(page2.at("/data/items/0/params/points").asInt()).isEqualTo(10);
        assertThat(page2.at("/data/nextCursor").isNull()).isTrue();
    }

    @Test
    void aMalformedCursorIsValidationFailed() throws Exception {
        mvc.perform(as(myToken, get("/api/v1/customer/notifications?cursor=%21%21")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("cursor"));
    }

    @Test
    void readingIsRecordedOnceAndAnotherCustomersNotificationIsNotFound() throws Exception {
        UUID id = notify(me, "POINTS_EARNED", Map.of());

        mvc.perform(as(myToken, post("/api/v1/customer/notifications/{id}/read", id))).andExpect(status().isNoContent());
        Object firstRead = readAt(id);
        mvc.perform(as(myToken, post("/api/v1/customer/notifications/{id}/read", id))).andExpect(status().isNoContent());

        assertThat(firstRead).isNotNull();
        assertThat(readAt(id)).isEqualTo(firstRead);
        mvc.perform(as(otherToken, post("/api/v1/customer/notifications/{id}/read", id)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ---------------------------------------------------------------------------------------------- the devices

    /** PO decision 10 Oct 2026: phone verified or not. */
    @Test
    void anUnverifiedCustomerRegistersADeviceAndTheSameTokenIsSeenAgainNotDuplicated() throws Exception {
        String token = accessTokens.issue(AuthSubjectType.CUSTOMER, aUser().unverified().create().id());

        JsonNode first = body(mvc.perform(registerDevice(token, "fcm-token-a", "IOS"))
                .andExpect(status().isCreated()).andReturn());
        JsonNode again = body(mvc.perform(registerDevice(token, "fcm-token-a", "IOS"))
                .andExpect(status().isCreated()).andReturn());

        assertThat(first.at("/data/platform").asText()).isEqualTo("IOS");
        assertThat(again.at("/data/id")).isEqualTo(first.at("/data/id"));
        assertThat(db.sql("SELECT count(*) FROM notification.device_token WHERE token = 'fcm-token-a'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    /** PO decision 10 Oct 2026: a shared phone's token moves to the customer who registers it now. */
    @Test
    void aTokenActiveForAnotherCustomerMovesToTheNewOneAndTheOldRowIsRevokedNotDeleted() throws Exception {
        JsonNode old = body(mvc.perform(registerDevice(otherToken, "fcm-shared-phone", "ANDROID"))
                .andExpect(status().isCreated()).andReturn());
        JsonNode mine = body(mvc.perform(registerDevice(myToken, "fcm-shared-phone", "ANDROID"))
                .andExpect(status().isCreated()).andReturn());

        assertThat(mine.at("/data/id")).isNotEqualTo(old.at("/data/id"));
        assertThat(db.sql("""
                        SELECT user_id::text || ':' || (revoked_at IS NULL) FROM notification.device_token
                         WHERE token = 'fcm-shared-phone' ORDER BY created_at, revoked_at NULLS LAST""")
                .query(String.class).list()).hasSize(2).contains(me + ":true");
    }

    @Test
    void revokingIsIdempotentNeverDeletesAndAnotherCustomersDeviceIsNotFound() throws Exception {
        String id = body(mvc.perform(registerDevice(myToken, "fcm-token-revoke", "ANDROID"))
                .andExpect(status().isCreated()).andReturn()).at("/data/id").asText();

        mvc.perform(as(otherToken, post("/api/v1/customer/notifications/devices/{id}/revoke", id)))
                .andExpect(status().isNotFound());
        mvc.perform(as(myToken, post("/api/v1/customer/notifications/devices/{id}/revoke", id)))
                .andExpect(status().isNoContent());
        mvc.perform(as(myToken, post("/api/v1/customer/notifications/devices/{id}/revoke", id)))
                .andExpect(status().isNoContent());

        assertThat(db.sql("SELECT revoked_at IS NOT NULL FROM notification.device_token WHERE id = CAST(:id AS uuid)")
                .param("id", id).query(Boolean.class).single()).isTrue();
    }

    @Test
    void aBlankTokenOrAMissingPlatformIsValidationFailed() throws Exception {
        mvc.perform(registerDevice(myToken, " ", "ANDROID")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(as(myToken, post("/api/v1/customer/notifications/devices").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"t\"}")))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------------------------- admin try again

    @Test
    void theAdminTryAgainAnswersNothingToRetryForADeliveryThatIsNotDeadLetter() throws Exception {
        String admin = accessTokens.issue(AuthSubjectType.ADMIN, anAdmin().create().id());
        UUID inApp = db.sql("""
                        SELECT id FROM notification.notification_delivery WHERE notification_id = :id AND channel = 'IN_APP'""")
                .param("id", notify(me, "POINTS_EARNED", Map.of())).query(UUID.class).single();

        mvc.perform(as(admin, post("/api/v1/admin/notification-deliveries/{id}/recover", inApp)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"provider back\"}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NOTIFICATION_DELIVERY_NOT_DEAD_LETTER"));
        mvc.perform(as(admin, post("/api/v1/admin/notification-deliveries/{id}/recover", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"provider back\"}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(admin, post("/api/v1/admin/notification-deliveries/{id}/recover", inApp)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(myToken, post("/api/v1/admin/notification-deliveries/{id}/recover", inApp)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}")))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private UUID notify(UUID user, String code, Map<String, Object> params) {
        return runner.run(new Command<UUID>() {
            @Override
            public EligibilityCategory eligibilityCategory() {
                return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
            }

            @Override
            public CommandScope scope() {
                return CommandScope.shared(LockSet.empty());
            }

            @Override
            public UUID execute(CommandContext context) {
                return notifications.notify(context,
                        NotificationRequest.of(code, NotificationRecipientType.CUSTOMER, user, params));
            }
        });
    }

    private Object readAt(UUID id) {
        return db.sql("SELECT read_at FROM notification.notification WHERE id = :id").param("id", id).query()
                .singleValue();
    }

    private MockHttpServletRequestBuilder registerDevice(String token, String deviceToken, String platform) {
        return as(token, post("/api/v1/customer/notifications/devices").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + deviceToken + "\",\"platform\":\"" + platform + "\"}"));
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + token);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
