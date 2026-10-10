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
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
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
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.notification.internal.webpush.WebPushProperties;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-44 over HTTP: the E7-39 venue Web Push routes (PO decision 5 Oct 2026) through the real security chain, error
 * mapping and database: the public key, subscribe (seen again, not duplicated; moved from another account; revoked,
 * never deleted) and revoke.
 */
@AutoConfigureMockMvc
class VenuePushSubscriptionHttpTest extends IntegrationTest {

    private static final String ROUTES = "/api/v1/venue/push-subscriptions";

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    DataSource appDataSource;

    @Autowired
    ObjectMapper json;

    JdbcClient db;
    UUID me;
    String myToken;
    String otherToken;
    String p256dh;
    String auth;

    @BeforeEach
    void setUp() throws Exception {
        db = JdbcClient.create(appDataSource);
        me = aVenueAccount().create().id();
        myToken = accessTokens.issue(AuthSubjectType.VENUE, me);
        otherToken = accessTokens.issue(AuthSubjectType.VENUE, aVenueAccount().create().id());
        p256dh = browserKey();
        auth = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
    }

    @Test
    void theDashboardGetsTheVapidPublicKeyItSubscribesWith() throws Exception {
        mvc.perform(as(myToken, get(ROUTES + "/vapid-public-key")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicKey").value(WebPushProperties.DEV_PUBLIC_KEY));
    }

    @Test
    void theSameBrowserIsSeenAgainNotDuplicated() throws Exception {
        String endpoint = "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID();
        JsonNode first = body(mvc.perform(subscribe(myToken, endpoint, p256dh, auth, "Mozilla/5.0 Chrome"))
                .andExpect(status().isCreated()).andReturn());
        JsonNode again = body(mvc.perform(subscribe(myToken, endpoint, p256dh, auth, null))
                .andExpect(status().isCreated()).andReturn());

        assertThat(again.path("data").path("id")).isEqualTo(first.path("data").path("id"));
        assertThat(rows(endpoint)).containsExactly(me + ":true");
        assertThat(db.sql("SELECT user_agent FROM notification.venue_push_subscription WHERE endpoint = :e")
                .param("e", endpoint).query(String.class).single()).isEqualTo("Mozilla/5.0 Chrome");
    }

    /** A shared computer: the browser now belongs to the account that subscribed last; the old row is revoked. */
    @Test
    void anEndpointActiveForAnotherAccountOrWithNewKeysMovesHereAndTheOldRowIsRevokedNotDeleted() throws Exception {
        String endpoint = "https://updates.push.services.mozilla.com/wpush/v2/" + UUID.randomUUID();
        mvc.perform(subscribe(otherToken, endpoint, p256dh, auth, null)).andExpect(status().isCreated());
        mvc.perform(subscribe(myToken, endpoint, p256dh, auth, null)).andExpect(status().isCreated());
        mvc.perform(subscribe(myToken, endpoint, browserKey(), auth, null)).andExpect(status().isCreated());

        assertThat(rows(endpoint)).hasSize(3).containsOnlyOnce(me + ":true");
    }

    @Test
    void revokingIsIdempotentNeverDeletesAndAnotherAccountsSubscriptionIsNotFound() throws Exception {
        String endpoint = "https://wns2-par02p.notify.windows.com/w/?token=" + UUID.randomUUID();
        String id = body(mvc.perform(subscribe(myToken, endpoint, p256dh, auth, null)).andReturn())
                .path("data").path("id").asText();

        mvc.perform(as(otherToken, post(ROUTES + "/" + id + "/revoke"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(as(myToken, post(ROUTES + "/" + id + "/revoke"))).andExpect(status().isNoContent());
        mvc.perform(as(myToken, post(ROUTES + "/" + id + "/revoke"))).andExpect(status().isNoContent());

        assertThat(rows(endpoint)).containsExactly(me + ":false");
    }

    @Test
    void anEndpointOutsideTheKnownPushServicesOrBrokenKeysAreValidationFailedOnTheField() throws Exception {
        expectInvalid(subscribe(myToken, "https://169.254.169.254/latest/meta-data", p256dh, auth, null), "endpoint");
        expectInvalid(subscribe(myToken, "https://evil.example.test/push", p256dh, auth, null), "endpoint");
        expectInvalid(subscribe(myToken, "https://user@fcm.googleapis.com/fcm/send/x", p256dh, auth, null),
                "endpoint");
        expectInvalid(subscribe(myToken, "https://fcm.googleapis.com/fcm/send/x", "BAAA", auth, null),
                "keys.p256dh");
        expectInvalid(subscribe(myToken, "https://fcm.googleapis.com/fcm/send/x", p256dh, "c2hvcnQ", null),
                "keys.auth");
        mvc.perform(as(myToken, post(ROUTES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"http://fcm.googleapis.com/x\",\"keys\":{\"p256dh\":\"a\",\"auth\":\"b\"}}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void onlyAVenueAccountReachesTheRoutes() throws Exception {
        String customer = accessTokens.issue(AuthSubjectType.CUSTOMER, aUser().verified().create().id());
        mvc.perform(as(customer, get(ROUTES + "/vapid-public-key"))).andExpect(status().isForbidden());
        mvc.perform(get(ROUTES + "/vapid-public-key")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------------------------------------------- helpers

    private void expectInvalid(MockHttpServletRequestBuilder request, String field) throws Exception {
        mvc.perform(request).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value(field));
    }

    private MockHttpServletRequestBuilder subscribe(String token, String endpoint, String p256dh, String auth,
                                                    String userAgent) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("endpoint", endpoint);
        body.put("keys", Map.of("p256dh", p256dh, "auth", auth));
        if (userAgent != null) {
            body.put("userAgent", userAgent);
        }
        return as(token, post(ROUTES).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    /** {@code account:active} per row of the endpoint, oldest first. */
    private java.util.List<String> rows(String endpoint) {
        return db.sql("""
                        SELECT venue_account_id::text || ':' || (revoked_at IS NULL)
                          FROM notification.venue_push_subscription WHERE endpoint = :e ORDER BY created_at, id""")
                .param("e", endpoint).query(String.class).list();
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + token);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** A browser's p256dh: a fresh P-256 public key as the 65-byte uncompressed point, URL-safe Base64. */
    private static String browserKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        ECPublicKey key = (ECPublicKey) generator.generateKeyPair().getPublic();
        byte[] point = new byte[65];
        point[0] = 0x04;
        copy(key.getW().getAffineX(), point, 1);
        copy(key.getW().getAffineY(), point, 33);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(point);
    }

    private static void copy(BigInteger value, byte[] out, int offset) {
        byte[] bytes = value.toByteArray();
        int start = Math.max(0, bytes.length - 32);
        System.arraycopy(bytes, start, out, offset + 32 - (bytes.length - start), bytes.length - start);
    }
}
