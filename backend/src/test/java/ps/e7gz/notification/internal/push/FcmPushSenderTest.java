package ps.e7gz.notification.internal.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * E7-43: the FCM HTTP v1 adapter without a Firebase project (PO decision 10 Oct 2026: the accounts come before
 * deployment). The message reaches Android and, through APNs, iPhones (arch 01 decision A7); errors are classified as
 * arch 08 section 4.3 says; the OAuth assertion is a valid RS256 JWT for the service account.
 */
class FcmPushSenderTest {

    private static final String TOKEN_URI = "https://oauth2.example.test/token";
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private final ObjectMapper json = new ObjectMapper();
    private KeyPair keys;
    private MockRestServiceServer server;
    private FcmPushSender sender;
    private FcmAccessTokens tokens;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String serviceAccount = Base64.getEncoder().encodeToString(json.writeValueAsBytes(Map.of(
                "project_id", "e7gz-test", "client_email", "push@e7gz-test.iam.example.test",
                "private_key", pem, "token_uri", TOKEN_URI)));

        RestClient.Builder builder = RestClient.builder().baseUrl("https://fcm.example.test");
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient http = builder.build();
        tokens = new FcmAccessTokens(FcmAccessTokens.ServiceAccount.parse(serviceAccount, json), http, json,
                Clock.fixed(NOW, ZoneOffset.UTC));
        sender = new FcmPushSender(http, tokens, json);
    }

    @Test
    void theMessageCarriesTheTextTheDeepLinkAndTheApnsAlertForIPhones() {
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URI)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"at-1\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://fcm.example.test/v1/projects/e7gz-test/messages:send"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer at-1"))
                .andExpect(jsonPath("$.message.token").value("device-1"))
                .andExpect(jsonPath("$.message.notification.title").value("Booking confirmed"))
                .andExpect(jsonPath("$.message.data.bookingId").value("b-1"))
                .andExpect(jsonPath("$.message.android.priority").value("HIGH"))
                .andExpect(jsonPath("$.message.apns.headers['apns-push-type']").value("alert"))
                .andExpect(jsonPath("$.message.apns.headers['apns-priority']").value("10"))
                .andExpect(jsonPath("$.message.apns.payload.aps.sound").value("default"))
                .andRespond(withSuccess("{\"name\":\"projects/e7gz-test/messages/1\"}", MediaType.APPLICATION_JSON));

        PushSender.PushResult result = sender.send("device-1", new PushSender.PushMessage("Booking confirmed",
                "See you there", Map.of("code", "BOOKING_CONFIRMED", "bookingId", "b-1")));

        assertThat(result).isEqualTo(new PushSender.PushResult.Sent("projects/e7gz-test/messages/1"));
        server.verify();
    }

    @Test
    void theAccessTokenIsKeptUntilItIsAboutToExpire() {
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URI))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andRespond(withSuccess("{\"access_token\":\"at-1\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));

        assertThat(tokens.current()).isEqualTo("at-1");
        assertThat(tokens.current()).isEqualTo("at-1");
        server.verify();
    }

    @Test
    void anUnregisteredTokenIsInvalidAndA503IsRetried() {
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"access_token\":\"at-1\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://fcm.example.test/v1/projects/e7gz-test/messages:send"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error":{"code":404,"status":"NOT_FOUND","details":[
                          {"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"UNREGISTERED"}]}}"""));
        server.expect(requestTo("https://fcm.example.test/v1/projects/e7gz-test/messages:send"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        PushSender.PushMessage message = new PushSender.PushMessage("t", "b", Map.of());
        assertThat(sender.send("gone", message)).isEqualTo(new PushSender.PushResult.InvalidToken("FCM_UNREGISTERED"));
        assertThat(sender.send("any", message)).isEqualTo(new PushSender.PushResult.Transient("FCM_HTTP_503"));
    }

    @Test
    void errorCodesAreClassifiedAsArch08Says() {
        assertThat(FcmPushSender.classify(400, "INVALID_ARGUMENT")).isInstanceOf(PushSender.PushResult.InvalidToken.class);
        assertThat(FcmPushSender.classify(403, "SENDER_ID_MISMATCH")).isInstanceOf(PushSender.PushResult.InvalidToken.class);
        assertThat(FcmPushSender.classify(429, "QUOTA_EXCEEDED")).isInstanceOf(PushSender.PushResult.Transient.class);
        assertThat(FcmPushSender.classify(401, "THIRD_PARTY_AUTH_ERROR")).isInstanceOf(PushSender.PushResult.Transient.class);
        assertThat(FcmPushSender.classify(500, "")).isInstanceOf(PushSender.PushResult.Transient.class);
    }

    @Test
    void theAssertionIsAnRs256JwtForTheServiceAccountAndItsTokenEndpoint() throws Exception {
        String[] parts = tokens.assertion(NOW).split("\\.");

        JsonNode header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(header.get("alg").asText()).isEqualTo("RS256");
        assertThat(claims.get("iss").asText()).isEqualTo("push@e7gz-test.iam.example.test");
        assertThat(claims.get("aud").asText()).isEqualTo(TOKEN_URI);
        assertThat(claims.get("scope").asText()).isEqualTo(FcmAccessTokens.SCOPE);
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(3600);

        Signature rsa = Signature.getInstance("SHA256withRSA");
        rsa.initVerify(keys.getPublic());
        rsa.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(rsa.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    @Test
    void aBrokenServiceAccountFailsWithoutQuotingIt() {
        String broken = Base64.getEncoder().encodeToString("{\"private_key\":\"SECRET-KEY-MATERIAL\"}".getBytes());

        assertThatThrownBy(() -> FcmAccessTokens.ServiceAccount.parse(broken, json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("SECRET-KEY-MATERIAL");
    }
}
